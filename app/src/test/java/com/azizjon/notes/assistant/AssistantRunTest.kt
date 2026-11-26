package com.azizjon.notes.assistant

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A stand-in for the gateway that plays a fixed list of tool calls, then replies. */
private class ScriptedGateway(
    private val calls: List<Pair<String, JSONObject>>,
    private val reply: String,
    private val failAt: Int? = null,
) : AgentGateway {
    val turnId = UUID.randomUUID().toString()
    var system: String? = null
    var input: String? = null
    var model: String? = null
    var effort: String? = null
    val results = mutableListOf<Pair<String, Boolean>>()
    var cancelled = false
    /** Completed when the call at this step is handed out, for tests that stop a turn halfway. */
    val reached = CompletableDeferred<Unit>()
    var waitAfter: Int? = null
    private var step = 0

    override val configured = true

    override suspend fun start(system: String, input: String, tools: JSONArray, maxSteps: Int, model: String?, effort: String?): AgentEvent {
        this.system = system
        this.input = input
        this.model = model
        this.effort = effort
        return next()
    }

    override suspend fun submit(turnId: String, callId: String, output: String, isError: Boolean): AgentEvent {
        results += output to isError
        if (waitAfter == results.size) {
            reached.complete(Unit)
            CompletableDeferred<Unit>().await() // until cancelled
        }
        return next()
    }

    override suspend fun cancel(turnId: String) {
        cancelled = true
    }

    private fun next(): AgentEvent {
        if (failAt == step) throw GatewayException("The assistant is unavailable. Try again later.")
        val call = calls.getOrNull(step++) ?: return AgentEvent.Done(turnId, reply, calls.size, 10)
        return AgentEvent.ToolCall(turnId, "call_$step", call.first, call.second)
    }
}

class AssistantRunTest {
    private val clock = { ZonedDateTime.of(2026, 11, 26, 14, 5, 0, 0, ZoneId.of("Asia/Seoul")) }

    private fun repo() = FolderRepo.create(
        mapOf(
            "ASSISTANT.md" to "# Rules\n\nIdeas go to ideas/projects.md.\n",
            "reminders/README.md" to "# Reminders\n\nIntro.\n\n## Files\n\nNot sent.\n\n## How `upcoming.md` is written\n\nThree sections.\n\n" +
                "## Rules for the bot\n\n- Change only files in `reminders/`.\n\n## Rules for Claude sessions on the PC\n\nNot sent either.\n",
            "ideas/projects.md" to "# Project ideas\n",
        ),
    )

    private fun add(text: String, change: String) =
        "add_text" to JSONObject().put("path", "ideas/projects.md").put("text", text).put("change", change)

    @Test
    fun aTurnRunsItsToolsAndListsItsChanges() = runBlocking {
        val repo = repo()
        val gateway = ScriptedGateway(
            listOf(
                "read_file" to JSONObject().put("path", "ideas/projects.md"),
                add("1. A bus app.", "Added the bus app idea"),
            ),
            reply = "**Saved** the bus app idea in `ideas/projects.md`.",
        )
        var after: List<AssistantChange>? = null
        val assistant = Assistant(gateway, AssistantTools(repo), repo, afterTurn = { after = it; emptyList() }, clock = clock)
        val progress = mutableListOf<String>()
        val choice = AssistantChoice(AssistantModel.SONNET, AssistantEffort.LOW)
        val outcome = assistant.run("Idea: a bus app", emptyList(), TurnLog(), choice) { progress += it }

        assertNull(outcome.error)
        assertEquals("Saved the bus app idea in ideas/projects.md.", outcome.reply)
        assertEquals(listOf("Added the bus app idea"), outcome.changes.map { it.summary })
        assertEquals(outcome.changes, after)
        assertEquals(listOf("claude-sonnet-5-5", "low"), listOf(gateway.model, gateway.effort))
        assertEquals(listOf("# Project ideas", false), listOf(gateway.results[0].first, gateway.results[0].second))
        assertEquals(
            listOf("Thinking", "Reading ideas/projects.md", "Thinking", "Saving: Added the bus app idea", "Thinking"),
            progress,
        )
        // The prompt carries the repo's own rules and the list's writing rules, and nothing else of that README.
        val system = gateway.system!!
        assertTrue(system.contains("Ideas go to ideas/projects.md."))
        assertTrue(system.contains("Three sections.") && system.contains("Change only files in `reminders/`."))
        assertTrue(!system.contains("Not sent"))
        assertTrue(gateway.input!!.startsWith("Today is Thursday 2026-11-26, 14:05 (Asia/Seoul)."))
        assertTrue(gateway.input!!.endsWith("The user's message:\nIdea: a bus app"))
    }

    @Test
    fun aFailedTurnKeepsWhatItSaved() = runBlocking {
        val repo = repo()
        val gateway = ScriptedGateway(listOf(add("1. A bus app.", "Added the bus app idea")), reply = "", failAt = 1)
        val outcome = Assistant(gateway, AssistantTools(repo), repo, clock = clock).run("Idea", emptyList(), TurnLog()) {}
        assertEquals("The assistant is unavailable. Try again later.", outcome.error)
        assertEquals(listOf("Added the bus app idea"), outcome.changes.map { it.summary })
        assertNull(outcome.reply)
    }

    @Test
    fun aToolErrorGoesBackToTheModelAsAnError() = runBlocking {
        val repo = repo()
        val gateway = ScriptedGateway(listOf("read_file" to JSONObject().put("path", "nope.md")), reply = "There is no such note.")
        val outcome = Assistant(gateway, AssistantTools(repo), repo, clock = clock).run("Read nope", emptyList(), TurnLog()) {}
        assertEquals(true, gateway.results.single().second)
        assertEquals("There is no such note.", outcome.reply)
        assertTrue(outcome.changes.isEmpty())
    }

    @Test
    fun stoppingATurnCancelsItOnTheGatewayAndKeepsItsChanges() = runBlocking {
        val repo = repo()
        val gateway = ScriptedGateway(
            listOf(add("1. A bus app.", "Added the bus app idea"), add("2. A fridge app.", "Added the fridge app idea")),
            reply = "Done.",
        ).apply { waitAfter = 1 }
        val log = TurnLog()
        val assistant = Assistant(gateway, AssistantTools(repo), repo, clock = clock)
        val turn = async(Dispatchers.Default) { assistant.run("Two ideas", emptyList(), log) {} }
        gateway.reached.await()
        turn.cancel()
        val stopped = runCatching { turn.await() }.exceptionOrNull()
        assertTrue(stopped is CancellationException)
        assertTrue(gateway.cancelled)
        assertEquals(listOf("Added the bus app idea"), log.changes.map { it.summary })
    }

    @Test
    fun earlierMessagesGoAlongForFollowUps() {
        val input = AssistantPrompt.input(
            "No, make it Saturday",
            listOf(AssistantPrompt.Earlier("Remind me to call Adam on Friday", "Added a reminder for Friday.", listOf("Added a reminder"))),
            clock(),
        )
        assertEquals(
            "Today is Thursday 2026-11-26, 14:05 (Asia/Seoul).\n\nEarlier in this conversation, oldest first:\n" +
                "- The user said: \"Remind me to call Adam on Friday\"\n  You replied: \"Added a reminder for Friday.\"\n" +
                "  Your changes: Added a reminder\n\nThe user's message:\nNo, make it Saturday",
            input,
        )
    }
}
