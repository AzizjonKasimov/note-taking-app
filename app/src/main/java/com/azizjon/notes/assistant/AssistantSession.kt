package com.azizjon.notes.assistant

import com.azizjon.notes.life.FileEdit
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The conversation with the assistant since the app started: each message, how its turn went, and
 * each change it made, with Undo. It lives as long as the app does, so a turn goes on while the
 * user looks at a note, and is kept in memory only.
 */
class AssistantSession(
    private val scope: CoroutineScope,
    private val assistant: Assistant,
    private val repo: AssistantRepo,
    private val choices: AssistantChoiceStore? = null,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    enum class UndoState { KEPT, UNDOING, UNDONE, CANNOT }

    data class ChangeItem(val change: AssistantChange, val undo: UndoState = UndoState.KEPT)

    sealed interface Status {
        data class Working(val progress: String) : Status
        data object Done : Status
        data class Failed(val error: String) : Status
        data object Stopped : Status
    }

    data class Exchange(
        val id: Long,
        val message: String,
        val status: Status,
        val reply: String? = null,
        val changes: List<ChangeItem> = emptyList(),
        /** Where the message was saved in the inbox, when the user chose that after a failure. */
        val savedTo: String? = null,
        /** The model and thinking level it ran with, as the box showed them. */
        val ranWith: String? = null,
        val hidden: Boolean = false,
    )

    private val _exchanges = MutableStateFlow<List<Exchange>>(emptyList())
    val exchanges: StateFlow<List<Exchange>> = _exchanges.asStateFlow()

    val configured: Boolean get() = assistant.configured

    /** The model and thinking level the next message runs with. */
    val choice: StateFlow<AssistantChoice> = choices?.choice ?: MutableStateFlow(AssistantChoice.DEFAULT).asStateFlow()

    fun choose(choice: AssistantChoice) {
        choices?.set(choice)
    }

    private var job: Job? = null
    private var nextId = 1L

    val running: Boolean get() = job?.isActive == true

    /** Starts a turn for [message]. False when one is running already, or there is nothing to send. */
    fun send(message: String): Boolean {
        val text = message.trim()
        if (text.isEmpty() || running) return false
        val id = nextId++
        val earlier = _exchanges.value
            .filter { it.status == Status.Done || it.changes.isNotEmpty() }
            .takeLast(MAX_EARLIER)
            .map { e ->
                AssistantPrompt.Earlier(e.message, e.reply, e.changes.filter { it.undo != UndoState.UNDONE }.map { it.change.summary })
            }
        val picked = choice.value
        _exchanges.update { list -> list + Exchange(id, text, Status.Working(Assistant.THINKING), ranWith = picked.label) }
        val log = TurnLog()
        job = scope.launch {
            try {
                val outcome = assistant.run(text, earlier, log, picked) { progress ->
                    update(id) { it.copy(status = Status.Working(progress), changes = merged(it.changes, log.changes)) }
                }
                update(id) {
                    it.copy(
                        status = outcome.error?.let(Status::Failed) ?: Status.Done,
                        reply = outcome.reply,
                        changes = merged(it.changes, outcome.changes),
                    )
                }
            } catch (stopped: CancellationException) {
                update(id) { it.copy(status = Status.Stopped, changes = merged(it.changes, log.changes)) }
                throw stopped
            }
        }
        return true
    }

    fun stop() {
        job?.cancel()
    }

    /** Hides an exchange's card; it still counts as context for the next message. */
    fun dismiss(id: Long) = update(id) { it.copy(hidden = true) }

    /** Takes back one change, keeping later edits to other lines. */
    fun undo(id: Long, index: Int) {
        val item = _exchanges.value.firstOrNull { it.id == id }?.changes?.getOrNull(index) ?: return
        if (item.undo != UndoState.KEPT) return
        setUndo(id, index, UndoState.UNDOING)
        scope.launch {
            var undone = false
            runCatching {
                repo.commitEdit { read ->
                    val files = Undo.revert(item.change, read) ?: return@commitEdit null
                    undone = true
                    FileEdit("${AssistantTools.COMMIT_PREFIX}undo: ${item.change.summary}", files)
                }
            }
            setUndo(id, index, if (undone) UndoState.UNDONE else UndoState.CANNOT)
        }
    }

    /** Saves a message the assistant could not handle as a note of its own in the inbox folder. */
    fun saveToInbox(id: Long) {
        val exchange = _exchanges.value.firstOrNull { it.id == id } ?: return
        if (exchange.savedTo != null) return
        scope.launch {
            var path: String? = null
            runCatching {
                repo.commitEdit { read ->
                    val stamp = now().format(INBOX_NAME)
                    var candidate = "$INBOX/$stamp.md"
                    var n = 2
                    while (read(candidate) != null) candidate = "$INBOX/$stamp-${n++}.md"
                    path = candidate
                    FileEdit(
                        "${AssistantTools.COMMIT_PREFIX}saved to the inbox: ${exchange.message.take(60)}",
                        mapOf(candidate to exchange.message.trim() + "\n"),
                    )
                }
            }
            path?.let { saved -> update(id) { it.copy(savedTo = saved) } }
        }
    }

    private fun setUndo(id: Long, index: Int, state: UndoState) = update(id) { e ->
        e.copy(changes = e.changes.mapIndexed { i, c -> if (i == index) c.copy(undo = state) else c })
    }

    private fun update(id: Long, change: (Exchange) -> Exchange) {
        _exchanges.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    /** The turn's changes, keeping the undo state of those already shown. */
    private fun merged(shown: List<ChangeItem>, changes: List<AssistantChange>): List<ChangeItem> =
        changes.mapIndexed { i, change -> shown.getOrNull(i)?.takeIf { it.change == change } ?: ChangeItem(change) }

    companion object {
        /** How many earlier messages go with a new one, for follow-ups. */
        const val MAX_EARLIER = 4
        const val INBOX = "inbox"
        private val INBOX_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
    }
}
