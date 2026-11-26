package com.azizjon.notes.assistant

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class GatewayException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The next thing an agent turn needs from the phone. */
sealed interface AgentEvent {
    val turnId: String

    /** Run [name] with [input] and post its result. */
    data class ToolCall(override val turnId: String, val callId: String, val name: String, val input: JSONObject) : AgentEvent

    /** The turn is over; [text] is the reply. */
    data class Done(override val turnId: String, val text: String, val toolCalls: Int, val durationMs: Long) : AgentEvent
}

/** The AI gateway's agent turns: Claude plans there, and every tool runs on the phone. */
interface AgentGateway {
    val configured: Boolean

    /** [model] and [effort] are the gateway's ids; null leaves the gateway's or the model's default. */
    suspend fun start(system: String, input: String, tools: JSONArray, maxSteps: Int, model: String?, effort: String?): AgentEvent

    suspend fun submit(turnId: String, callId: String, output: String, isError: Boolean): AgentEvent

    /** Best effort: a turn the gateway already ended has nothing to stop. */
    suspend fun cancel(turnId: String)
}

/**
 * Speaks the gateway's agent protocol (`POST /v1/agent/turns`, then one POST per tool result).
 * Every exchange is one ordinary request, so there is no socket to keep open and nothing to
 * resume: a dropped exchange fails the turn. The token is the user's own, entered in Settings and
 * never bundled, since the app's releases are public.
 */
class GatewayClient(
    private val token: () -> String?,
    private val baseUrl: String = BASE_URL,
) : AgentGateway {
    override val configured: Boolean get() = !token().isNullOrBlank()

    override suspend fun start(
        system: String,
        input: String,
        tools: JSONArray,
        maxSteps: Int,
        model: String?,
        effort: String?,
    ): AgentEvent = parseEvent(post("$baseUrl/v1/agent/turns", startBody(system, input, tools, maxSteps, model, effort)))

    override suspend fun submit(turnId: String, callId: String, output: String, isError: Boolean): AgentEvent =
        parseEvent(
            post(
                "$baseUrl/v1/agent/turns/${checkedTurnId(turnId)}/results",
                JSONObject().put("call_id", callId).put("output", output.take(MAX_TOOL_OUTPUT)).put("is_error", isError),
            ),
        )

    override suspend fun cancel(turnId: String) {
        runCatching { post("$baseUrl/v1/agent/turns/${checkedTurnId(turnId)}/cancel", JSONObject()) }
    }

    private suspend fun post(url: String, body: JSONObject): String = withContext(Dispatchers.IO) {
        val key = token()?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw GatewayException(NO_KEY)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $key")
        }
        // A turn is a burst of requests to one host: keeping a cleanly read connection open lets
        // the next one skip a fresh TLS handshake.
        var reusable = false
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NO_CONTENT) return@withContext ""
            val response = readLimited(if (status in 200..299) connection.inputStream else connection.errorStream)
            if (status !in 200..299) throw GatewayException(failureReason(status, response))
            reusable = true
            response
        } catch (error: GatewayException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw GatewayException("The assistant stopped answering. Anything it saved is listed below.", error)
        } catch (error: IOException) {
            throw GatewayException("Could not reach the assistant. Check the connection and try again.", error)
        } finally {
            if (!reusable) connection.disconnect()
        }
    }

    companion object {
        /** The user's own AI gateway. It picks the model, so changing that needs no app release. */
        const val BASE_URL = "https://ai.204-168-198-233.sslip.io"

        const val NO_KEY = "Add your AI gateway key in Settings first."
        const val MAX_TOOL_OUTPUT = 90_000
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val CONNECT_TIMEOUT_MILLIS = 15_000

        /** One model step, which can be a long reply. Caddy gives up at 180 s. */
        private const val READ_TIMEOUT_MILLIS = 170_000

        private val TURN_ID = Regex("^[0-9a-f-]{36}$")

        fun startBody(
            system: String,
            input: String,
            tools: JSONArray,
            maxSteps: Int,
            model: String? = null,
            effort: String? = null,
        ): JSONObject = JSONObject().put("system", system).put("input", input).put("tools", tools).put("max_steps", maxSteps)
            .apply { model?.let { put("model", it) } }
            .apply { effort?.let { put("effort", it) } }

        fun parseEvent(body: String): AgentEvent {
            val json = runCatching { JSONObject(body) }.getOrElse { throw GatewayException(UNREADABLE, it) }
            val turnId = json.optString("turn_id")
            if (!TURN_ID.matches(turnId)) throw GatewayException(UNREADABLE)
            return when (json.optString("status")) {
                "tool_call" -> {
                    val call = json.optJSONObject("call") ?: throw GatewayException(UNREADABLE)
                    val name = call.optString("name")
                    val id = call.optString("id")
                    if (name.isBlank() || id.isBlank()) throw GatewayException(UNREADABLE)
                    AgentEvent.ToolCall(turnId, id, name, call.optJSONObject("input") ?: JSONObject())
                }
                "done" -> AgentEvent.Done(
                    turnId = turnId,
                    text = json.optString("text").trim(),
                    toolCalls = json.optInt("tool_calls"),
                    durationMs = json.optLong("duration_ms"),
                )
                else -> throw GatewayException(UNREADABLE)
            }
        }

        /** A gateway failure in words the user can act on. Matched as text, so tests need no JSON. */
        fun failureReason(status: Int, body: String = ""): String = when {
            status == 401 -> "The AI gateway key is wrong. Replace it in Settings."
            body.contains("queue_full", ignoreCase = true) -> "The assistant is busy. Try again in a moment."
            body.contains("upstream_rate_limited", ignoreCase = true) || status == 429 ->
                "The Claude usage limit was reached. Try again later."
            body.contains("agent_max_steps", ignoreCase = true) ->
                "That took the assistant too many steps. Try asking for less at once."
            body.contains("tool_result_timeout", ignoreCase = true) -> "The assistant stopped waiting for this phone. Try again."
            body.contains("agent_unavailable", ignoreCase = true) -> "The gateway cannot run the assistant right now."
            body.contains("unknown_turn", ignoreCase = true) -> "The assistant lost track of this request. Try again."
            body.contains("model_not_allowed", ignoreCase = true) -> "The gateway does not let this app use that model. Pick another."
            status == 413 -> "That message is too long to send."
            status == 422 -> "The assistant declined this request. Try other words."
            status == 400 -> "The gateway turned the request down. Update the app."
            status == 409 -> "The request was stopped before it finished."
            status == 504 -> "The assistant took too long to answer. Try again."
            status in 500..599 -> "The assistant is unavailable. Try again later."
            else -> "The gateway answered with HTTP $status."
        }

        private const val UNREADABLE = "The assistant sent an answer this app cannot read."

        private fun checkedTurnId(turnId: String): String {
            if (!TURN_ID.matches(turnId)) throw GatewayException(UNREADABLE)
            return turnId
        }

        private fun readLimited(input: InputStream?): String {
            if (input == null) return ""
            return input.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4_096)
                var total = 0
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_RESPONSE_BYTES) throw GatewayException("The assistant's answer was too large to read.")
                    out.write(buffer, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
        }
    }
}
