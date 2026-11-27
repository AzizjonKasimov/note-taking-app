package com.azizjon.notes.update

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
    /** The APK's size and SHA-256 from the manifest; a download that differs is deleted unopened. */
    val apkSizeBytes: Long,
    val sha256: String,
)

/**
 * Checks a small JSON manifest for a newer release, downloads the signed APK, and
 * hands it to the system package installer.
 *
 * The manifest is `version.json` on the releases repo's main branch:
 * `{ "versionCode": N, "versionName": "x.y", "apkUrl": "https://github.com/…/releases/download/…",
 * "apkSizeBytes": N, "sha256": "…", "notes": "…" }`. The APK must come from that repo's releases, and
 * its size and SHA-256 must match, or nothing is installed: since 2.5 the app also holds People
 * (Network App until then), whose rules ask for this check (`release.ps1` writes both values).
 */
class UpdateManager(private val context: Context) {

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val body = httpGet(MANIFEST_URL) ?: return@withContext null
        parseUpdateManifest(body, currentVersionCode())
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
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("The update server returned HTTP $code")
                val reported = conn.contentLengthLong
                if (reported > 0 && reported != info.apkSizeBytes) {
                    throw IOException("The update's size does not match the release")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                var lastProgress = -1
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloaded += read
                            if (downloaded > info.apkSizeBytes) throw IOException("The update is larger than the release says")
                            val progress = ((downloaded * 100) / info.apkSizeBytes).toInt().coerceAtMost(100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
                if (!integrityMatches(downloaded, digest.digest().toHex(), info.apkSizeBytes, info.sha256)) {
                    throw IOException("The update failed its check against the release")
                }
                out
            } catch (failure: Exception) {
                out.delete()
                throw failure
            } finally {
                conn.disconnect()
            }
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
        try {
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            conn.disconnect()
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
        const val MANIFEST_URL =
            "https://raw.githubusercontent.com/AzizjonKasimov/note-taking-app-releases/main/version.json"
    }
}

/** The release the manifest offers, or null when it is no newer, or when anything in it fails a check. */
internal fun parseUpdateManifest(body: String, currentVersionCode: Long): UpdateInfo? {
    val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val info = UpdateInfo(
        versionCode = json.optLong("versionCode", -1),
        versionName = json.optString("versionName", ""),
        apkUrl = json.optString("apkUrl", ""),
        notes = json.optString("notes", ""),
        apkSizeBytes = json.optLong("apkSizeBytes", -1),
        sha256 = json.optString("sha256", "").lowercase(),
    )
    val trusted = info.versionName.isNotBlank() &&
        isTrustedReleaseUrl(info.apkUrl) &&
        info.apkSizeBytes in 1..MAX_APK_SIZE_BYTES &&
        info.sha256.matches(Regex("[0-9a-f]{64}"))
    return if (trusted && info.versionCode > currentVersionCode) info else null
}

internal fun integrityMatches(actualSize: Long, actualSha256: String, expectedSize: Long, expectedSha256: String): Boolean =
    actualSize == expectedSize && actualSha256.equals(expectedSha256, ignoreCase = true)

/** Only an APK from the releases repo's own GitHub releases. */
private fun isTrustedReleaseUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" &&
        uri.host == "github.com" &&
        uri.port == -1 &&
        uri.userInfo == null &&
        uri.query == null &&
        uri.fragment == null &&
        uri.path.startsWith("/AzizjonKasimov/note-taking-app-releases/releases/download/")
}.getOrDefault(false)

private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** The release APK is about 100 MB (the speech engine); this leaves room without trusting any size. */
private const val MAX_APK_SIZE_BYTES = 400L * 1024L * 1024L
