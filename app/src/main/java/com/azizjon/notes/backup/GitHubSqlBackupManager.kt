package com.azizjon.notes.backup

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class GitHubBackupException(message: String) : Exception(message)

data class GitHubBackupResult(
    val notebooks: Int,
    val notes: Int,
)

class GitHubSqlBackupManager(
    private val context: Context,
    private val settings: GitHubBackupSettings,
) {

    suspend fun backup(config: GitHubBackupConfig, data: BackupData): GitHubBackupResult =
        withContext(Dispatchers.IO) {
            requireConfigured(config)
            val sql = SqlBackupCodec.serialize(data)
            val existing = getContent(config)
            putContent(
                config = config,
                content = sql,
                sha = existing?.sha,
                message = "backup(android): ${System.currentTimeMillis()}",
            )
            settings.markBackedUp()
            GitHubBackupResult(data.notebooks.size, data.notes.size)
        }

    suspend fun restore(config: GitHubBackupConfig): BackupData = withContext(Dispatchers.IO) {
        requireConfigured(config)
        val content = getContent(config)?.text
            ?: throw GitHubBackupException("GitHub backup file not found")
        SqlBackupCodec.deserialize(context, content)
    }

    private fun requireConfigured(config: GitHubBackupConfig) {
        if (!config.configured) throw GitHubBackupException("GitHub backup settings are incomplete")
    }

    private fun getContent(config: GitHubBackupConfig): GitHubContent? {
        val url = contentUrl(config) + "?ref=${encode(config.branch)}"
        val response = request(config, "GET", url)
        if (response.code == 404) return null
        if (response.code !in 200..299) throw GitHubBackupException(response.message)
        val json = JSONObject(response.body)
        val encoded = json.optString("content").replace("\n", "")
        val text = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        return GitHubContent(sha = json.optString("sha"), text = text)
    }

    private fun putContent(
        config: GitHubBackupConfig,
        content: String,
        sha: String?,
        message: String,
    ) {
        val body = JSONObject()
            .put("message", message)
            .put("branch", config.branch)
            .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        if (!sha.isNullOrBlank()) body.put("sha", sha)
        val response = request(config, "PUT", contentUrl(config), body.toString().toByteArray(Charsets.UTF_8))
        if (response.code !in 200..299) throw GitHubBackupException(response.message)
    }

    private fun request(
        config: GitHubBackupConfig,
        method: String,
        url: String,
        body: ByteArray? = null,
    ): GitHubResponse {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer ${config.token}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return GitHubResponse(
                code = conn.responseCode,
                body = text,
                message = parseError(text).ifBlank { "GitHub HTTP ${conn.responseCode}" },
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun contentUrl(config: GitHubBackupConfig): String =
        "https://api.github.com/repos/${encode(config.owner)}/${encode(config.repo)}/contents/${encodePath(config.path)}"

    private fun encodePath(path: String): String =
        path.trim().trimStart('/').split('/').joinToString("/") { encode(it) }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun parseError(text: String): String =
        runCatching { JSONObject(text).optString("message") }.getOrDefault(text)

    private data class GitHubContent(
        val sha: String,
        val text: String,
    )

    private data class GitHubResponse(
        val code: Int,
        val body: String,
        val message: String,
    )
}
