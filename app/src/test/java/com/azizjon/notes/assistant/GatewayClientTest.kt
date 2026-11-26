package com.azizjon.notes.assistant

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayClientTest {
    private val id = "6f1c2a3b-0000-4000-8000-123456789abc"

    @Test
    fun readsAToolCallAndAReply() {
        val call = GatewayClient.parseEvent(
            """{"turn_id":"$id","status":"tool_call","call":{"id":"call_1","name":"read_file","input":{"path":"a.md"}}}""",
        ) as AgentEvent.ToolCall
        assertEquals(listOf(id, "call_1", "read_file", "a.md"), listOf(call.turnId, call.callId, call.name, call.input.getString("path")))
        val done = GatewayClient.parseEvent(
            """{"turn_id":"$id","status":"done","text":" Saved. ","model":"m","tool_calls":2,"usage":{},"duration_ms":1200}""",
        ) as AgentEvent.Done
        assertEquals(listOf("Saved.", 2, 1200L), listOf(done.text, done.toolCalls, done.durationMs))
    }

    @Test
    fun refusesAnswersItCannotRead() {
        for (body in listOf("not json", """{"turn_id":"x","status":"done"}""", """{"turn_id":"$id","status":"tool_call"}""", """{"turn_id":"$id"}""")) {
            assertThrows(GatewayException::class.java) { GatewayClient.parseEvent(body) }
        }
    }

    @Test
    fun saysWhatAFailureMeans() {
        assertTrue(GatewayClient.failureReason(401).contains("key is wrong"))
        assertTrue(GatewayClient.failureReason(429, """{"error":{"code":"queue_full"}}""").contains("busy"))
        assertTrue(GatewayClient.failureReason(429).contains("usage limit"))
        assertTrue(GatewayClient.failureReason(502, """{"error":{"code":"agent_max_steps"}}""").contains("too many steps"))
        assertTrue(GatewayClient.failureReason(503).contains("unavailable"))
    }

    @Test
    fun theStartBodyHasOnlyWhatTheGatewayTakes() {
        val plain = GatewayClient.startBody("s", "i", JSONArray(), 20)
        assertEquals(setOf("system", "input", "tools", "max_steps"), plain.keys().asSequence().toSet())
        val picked = GatewayClient.startBody("s", "i", JSONArray(), 20, model = "claude-opus-5-5", effort = "medium")
        assertEquals(listOf("claude-opus-5-5", "medium"), listOf(picked.getString("model"), picked.getString("effort")))
    }

    @Test
    fun theModelsOnOfferAreHaikuSonnetAndOpusAndNeverFable() {
        assertEquals(listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5"), AssistantModel.entries.map { it.id })
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), AssistantEffort.entries.map { it.id })
        assertEquals("Opus 5.5 · Medium", AssistantChoice.DEFAULT.label)
    }
}
