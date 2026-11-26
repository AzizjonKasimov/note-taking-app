package com.azizjon.notes.assistant

import com.azizjon.notes.life.TextMerge
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How a turn ended. A failed turn still lists what it saved before it failed, to undo. */
data class AssistantOutcome(val reply: String?, val changes: List<AssistantChange>, val error: String?)

/**
 * Runs one message through the assistant: Claude plans on the gateway, the tools run here, one
 * exchange per tool call, until Claude replies. [afterTurn] runs once a turn has changed files, for
 * the app's own follow-ups (the reminder list adds new events' checklist steps), and returns them
 * as changes of their own.
 */
class Assistant(
    private val gateway: AgentGateway,
    private val tools: AssistantTools,
    private val repo: AssistantRepo,
    private val afterTurn: suspend (List<AssistantChange>) -> List<AssistantChange> = { emptyList() },
    private val clock: () -> ZonedDateTime = ZonedDateTime::now,
) {
    val configured: Boolean get() = gateway.configured

    /**
     * [log] collects the changes as they are saved; it belongs to the caller, so a turn that is
     * stopped halfway still shows what it saved.
     */
    suspend fun run(
        message: String,
        earlier: List<AssistantPrompt.Earlier>,
        log: TurnLog,
        choice: AssistantChoice = AssistantChoice.DEFAULT,
        onProgress: (String) -> Unit,
    ): AssistantOutcome {
        var turnId: String? = null
        var reply: String? = null
        var error: String? = null
        try {
            val (rules, listRules) = repo.read { files ->
                runCatching { files.read(AssistantPrompt.RULES_FILE) }.getOrNull() to
                    runCatching { files.read(AssistantPrompt.LIST_RULES_FILE) }.getOrNull()
            }
            onProgress(THINKING)
            var event = gateway.start(
                system = AssistantPrompt.system(rules, listRules),
                input = AssistantPrompt.input(message, earlier, clock()),
                tools = tools.definitions(),
                maxSteps = MAX_STEPS,
                model = choice.model.id,
                // Always sent: the models' own defaults differ (Sonnet thinks at high, Opus and Haiku at medium).
                effort = choice.effort.id,
            )
            turnId = event.turnId
            while (event is AgentEvent.ToolCall) {
                val call: AgentEvent.ToolCall = event
                onProgress(tools.progressLabel(call.name, call.input))
                val result = tools.execute(call.name, call.input, log)
                onProgress(THINKING)
                event = gateway.submit(call.turnId, call.callId, result.output, result.isError)
            }
            reply = PlainReply.from((event as AgentEvent.Done).text).ifBlank { if (log.changes.isEmpty()) "Done." else "Saved." }
        } catch (cancelled: CancellationException) {
            // Left running, the turn would keep Claude busy on a reply nobody reads.
            turnId?.let { id -> withContext(NonCancellable) { withTimeoutOrNull(CANCEL_TIMEOUT_MILLIS) { gateway.cancel(id) } } }
            throw cancelled
        } catch (e: GatewayException) {
            error = e.message ?: FAILED
        } catch (e: Exception) {
            turnId?.let { id -> withTimeoutOrNull(CANCEL_TIMEOUT_MILLIS) { gateway.cancel(id) } }
            error = e.message ?: FAILED
        }
        val extra = if (log.changes.isEmpty()) emptyList() else runCatching { afterTurn(log.changes.toList()) }.getOrDefault(emptyList())
        return AssistantOutcome(reply, log.changes.toList() + extra, error)
    }

    companion object {
        /** Most model steps one message may take; the gateway caps it too. */
        const val MAX_STEPS = 20
        const val THINKING = "Thinking"
        private const val FAILED = "The assistant could not finish."
        private const val CANCEL_TIMEOUT_MILLIS = 5_000L
    }
}

/** Taking back one of the assistant's changes, while keeping later edits to other lines. */
object Undo {
    /**
     * The text each file of [change] should get to take it back, or null when a later edit
     * touched the same lines (or a file it made or deleted changed since), so only a person can
     * tell what to keep.
     */
    fun revert(change: AssistantChange, read: (String) -> String?): Map<String, String?>? {
        val out = LinkedHashMap<String, String?>()
        for (f in change.files) {
            val now = read(f.path)
            out[f.path] = when {
                now == f.after -> f.before
                now == null || f.after == null || f.before == null -> return null
                else -> TextMerge.merge(f.after, now, f.before).takeUnless { it.conflicted }?.text ?: return null
            }
        }
        return out
    }
}

/** Plain text for a reply: the app shows it as written, so stray Markdown is taken out. */
object PlainReply {
    private val LINK = Regex("""\[([^\]]+)]\((?:[^)\s]+)\)""")
    private val EMPHASIS = Regex("""(\*\*|__)(.+?)\1""")
    private val ITALIC = Regex("""(?<![\w*])\*(?!\s)([^*\n]+?)\*(?!\w)""")
    private val CODE = Regex("""`([^`\n]+)`""")
    private val HEADING = Regex("""(?m)^#{1,6}\s+""")
    private val BULLET = Regex("""(?m)^\s*[*-]\s+""")

    fun from(text: String): String = text
        .replace(LINK, "$1")
        .replace(EMPHASIS, "$2")
        .replace(ITALIC, "$1")
        .replace(CODE, "$1")
        .replace(HEADING, "")
        .replace(BULLET, "• ")
        .trim()
}
