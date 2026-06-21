package com.azizjon.notes.update

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
)

/**
 * Checks a small JSON manifest for a newer release, downloads the signed APK, and
 * hands it to the system package installer.
 *
 * Hosting-agnostic: [MANIFEST_URL] can point at a GitHub raw file, a release asset,
 * or any static URL that returns the expected JSON shape:
 * `{ "versionCode": N, "versionName": "x.y", "apkUrl": "https://…/app.apk", "notes": "…" }`
 */
class UpdateManager(private val context: Context) {

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val body = httpGet(MANIFEST_URL) ?: return@withContext null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        val info = UpdateInfo(
            versionCode = json.optLong("versionCode", -1),
            versionName = json.optString("versionName", ""),
            apkUrl = json.optString("apkUrl", ""),
            notes = json.optString("notes", ""),
        )
        if (info.apkUrl.isBlank() || info.versionCode <= currentVersionCode()) null else info
    }

    fun currentVersionCode(): Long {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
    }

    suspend fun downloadApk(info: UpdateInfo, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "notes-${info.versionCode}.apk")
            val conn = open(info.apkUrl)
            conn.connect()
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var downloaded = 0L
                    var read = input.read(buffer)
                    while (read != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) onProgress(((downloaded * 100) / total).toInt())
                        read = input.read(buffer)
                    }
                }
            }
            conn.disconnect()
            out
        }

    fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun httpGet(url: String): String? = try {
        val conn = open(url)
        if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Notes-Updater")
        }

    companion object {
        // Static JSON manifest describing the latest release.
        // Change this if you move hosting (e.g. to your own server).
        const val MANIFEST_URL =
            "https://raw.githubusercontent.com/AzizjonKasimov/note-taking-app-releases/main/version.json"
    }
}
