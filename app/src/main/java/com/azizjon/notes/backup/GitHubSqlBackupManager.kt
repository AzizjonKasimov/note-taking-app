package com.azizjon.notes.backup

import android.content.Context
import android.util.Base64
import com.azizjon.notes.data.NotebookImageStore
import com.azizjon.notes.data.NotebookMarkerType
import com.azizjon.notes.data.NotebookMediaPayload
import com.azizjon.notes.data.appearance
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
    val imagesUploaded: Int = 0,
    val cleanupWarnings: Int = 0,
)

data class GitHubRestoreResult(
    val data: BackupData,
    val media: List<NotebookMediaPayload>,
    val mediaDownloadWarnings: Int = 0,
)

data class GitHubContent(val sha: String, val bytes: ByteArray)

interface GitHubContentsClient {
    fun get(config: GitHubBackupConfig, path: String): GitHubContent?
    fun put(config: GitHubBackupConfig, path: String, bytes: ByteArray, sha: String?, message: String)
    fun delete(config: GitHubBackupConfig, path: String, sha: String, message: String)
}

class GitHubSqlBackupManager(
    private val context: Context,
    private val settings: GitHubBackupSettings,
    private val imageStore: NotebookImageStore,
    private val contents: GitHubContentsClient = HttpGitHubContentsClient(),
) {

    suspend fun backup(config: GitHubBackupConfig, data: BackupData): GitHubBackupResult =
        withContext(Dispatchers.IO) {
            requireConfigured(config)
            val existingSql = contents.get(config, config.path)
            val previousCustomIds = existingSql?.bytes?.let { bytes ->
                runCatching {
                    SqlBackupCodec.deserialize(context, bytes.toString(Charsets.UTF_8)).notebooks
                        .filter { it.appearance().type == NotebookMarkerType.CUSTOM_PHOTO }
                        .mapTo(mutableSetOf()) { it.id }
                }.getOrDefault(emptySet())
            }.orEmpty()
            val currentCustomIds = data.notebooks
                .filter { it.appearance().type == NotebookMarkerType.CUSTOM_PHOTO }
                .mapTo(linkedSetOf()) { it.id }

            var uploaded = 0
            currentCustomIds.forEach { id ->
                val payload = imageStore.readPayload(id)
                val source = payload.source
                    ?: throw GitHubBackupException("Notebook $id is missing its editable photo source")
                val crop = payload.crop
                    ?: throw GitHubBackupException("Notebook $id is missing its cropped marker")
                if (putIfChanged(config, mediaPath(config, id, SOURCE_FILE), source)) uploaded++
                if (putIfChanged(config, mediaPath(config, id, CROP_FILE), crop)) uploaded++
            }

            val sql = SqlBackupCodec.serialize(data).toByteArray(Charsets.UTF_8)
            contents.put(
                config = config,
                path = config.path,
                bytes = sql,
                sha = existingSql?.sha,
                message = "backup(android): ${System.currentTimeMillis()}",
            )

            var cleanupWarnings = 0
            (previousCustomIds - currentCustomIds).forEach { id ->
                listOf(SOURCE_FILE, CROP_FILE).forEach { fileName ->
                    val path = mediaPath(config, id, fileName)
                    runCatching {
                        contents.get(config, path)?.let { remote ->
                            contents.delete(config, path, remote.sha, "cleanup notebook marker $id")
                        }
                    }.onFailure { cleanupWarnings++ }
                }
            }

            settings.markBackedUp()
            GitHubBackupResult(data.notebooks.size, data.notes.size, uploaded, cleanupWarnings)
        }

    suspend fun restore(config: GitHubBackupConfig): GitHubRestoreResult = withContext(Dispatchers.IO) {
        requireConfigured(config)
        val sql = contents.get(config, config.path)?.bytes
            ?: throw GitHubBackupException("GitHub backup file not found")
        val data = SqlBackupCodec.deserialize(context, sql.toString(Charsets.UTF_8))
        var warnings = 0
        val media = data.notebooks
            .filter { it.appearance().type == NotebookMarkerType.CUSTOM_PHOTO }
            .map { notebook ->
                fun download(fileName: String): ByteArray? = runCatching {
                    contents.get(config, mediaPath(config, notebook.id, fileName))?.bytes
                }.onFailure { warnings++ }.getOrNull()
                NotebookMediaPayload(
                    notebookId = notebook.id,
                    source = download(SOURCE_FILE),
                    crop = download(CROP_FILE),
                )
            }
        GitHubRestoreResult(data, media, warnings)
    }

    private fun putIfChanged(config: GitHubBackupConfig, path: String, bytes: ByteArray): Boolean {
        val existing = contents.get(config, path)
        if (existing?.bytes?.contentEquals(bytes) == true) return false
        contents.put(
            config,
            path,
            bytes,
            existing?.sha,
            "backup notebook marker: ${System.currentTimeMillis()}",
        )
        return true
    }

    private fun requireConfigured(config: GitHubBackupConfig) {
        if (!config.configured) throw GitHubBackupException("GitHub backup settings are incomplete")
    }

    private fun mediaPath(config: GitHubBackupConfig, notebookId: Long, fileName: String): String {
        val sqlPath = config.path.trim().trimStart('/')
        val parent = sqlPath.substringBeforeLast('/', "")
        return listOf(parent, MEDIA_DIRECTORY, notebookId.toString(), fileName)
            .filter { it.isNotBlank() }
            .joinToString("/")
    }

    companion object {
        private const val MEDIA_DIRECTORY = "notebook-images"
        private const val SOURCE_FILE = "source.webp"
        private const val CROP_FILE = "crop.webp"
    }
}

internal class HttpGitHubContentsClient : GitHubContentsClient {
    override fun get(config: GitHubBackupConfig, path: String): GitHubContent? {
        val url = contentUrl(config, path) + "?ref=${encode(config.branch)}"
        val response = request(config, "GET", url)
        if (response.code == 404) return null
        if (response.code !in 200..299) throw GitHubBackupException(response.message)
        val json = JSONObject(response.body)
        val encoded = json.optString("content").replace("\n", "")
        return GitHubContent(json.optString("sha"), Base64.decode(encoded, Base64.DEFAULT))
    }

    override fun put(
        config: GitHubBackupConfig,
        path: String,
        bytes: ByteArray,
        sha: String?,
        message: String,
    ) {
        val body = JSONObject()
            .put("message", message)
            .put("branch", config.branch)
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
        if (!sha.isNullOrBlank()) body.put("sha", sha)
        val response = request(
            config,
            "PUT",
            contentUrl(config, path),
            body.toString().toByteArray(Charsets.UTF_8),
        )
        if (response.code !in 200..299) throw GitHubBackupException(response.message)
    }

    override fun delete(
        config: GitHubBackupConfig,
        path: String,
        sha: String,
        message: String,
    ) {
        val body = JSONObject().put("message", message).put("branch", config.branch).put("sha", sha)
        val response = request(
            config,
            "DELETE",
            contentUrl(config, path),
            body.toString().toByteArray(Charsets.UTF_8),
        )
        if (response.code !in 200..299 && response.code != 404) {
            throw GitHubBackupException(response.message)
        }
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

    private fun contentUrl(config: GitHubBackupConfig, path: String): String =
        "https://api.github.com/repos/${encode(config.owner)}/${encode(config.repo)}/contents/${encodePath(path)}"

    private fun encodePath(path: String): String =
        path.trim().trimStart('/').split('/').joinToString("/") { encode(it) }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun parseError(text: String): String =
        runCatching { JSONObject(text).optString("message") }.getOrDefault(text)

    private data class GitHubResponse(val code: Int, val body: String, val message: String)
}
