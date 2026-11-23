package com.azizjon.notes.voice

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val done: Long, val total: Long, val waitingForWifi: Boolean) : ModelState
    data object Checking : ModelState
    data class Ready(val dir: File) : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * The speech model on the phone: downloaded once with the system's DownloadManager (over Wi-Fi, in
 * the background, resumed after a restart), then checked file by file against its SHA-256.
 */
internal class ModelStore(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("voice_model", Context.MODE_PRIVATE)
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<ModelState> = _state.asStateFlow()
    private var watcher: Job? = null

    val dir: File
        get() = File(context.getExternalFilesDir(MODELS) ?: File(context.filesDir, MODELS), SpeechModel.ID)

    init {
        if (pending().isNotEmpty()) watch()
    }

    /** Starts (or resumes watching) the download. */
    fun download() {
        if (_state.value is ModelState.Ready || pending().isNotEmpty()) return
        dir.mkdirs()
        val enqueued = SpeechModel.files.filterNot { File(dir, it.name).length() == it.size }.map { file ->
            File(dir, file.name + PART).delete()
            val request = DownloadManager.Request(Uri.parse(SpeechModel.url(file)))
                .setTitle("Notes speech model")
                .setDescription(file.name)
                .setDestinationInExternalFilesDir(context, MODELS, "${SpeechModel.ID}/${file.name}$PART")
                .setAllowedOverMetered(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            "${downloads.enqueue(request)}:${file.name}"
        }
        if (enqueued.isEmpty()) {
            // Every file is already here (an earlier download ended before its check): just check them.
            watcher?.cancel()
            watcher = scope.launch(Dispatchers.IO) { verify() }
            return
        }
        prefs.edit().putString(KEY_PENDING, enqueued.joinToString(",")).apply()
        _state.value = ModelState.Downloading(0, SpeechModel.totalBytes, waitingForWifi = false)
        watch()
    }

    /** Cancels a download, or deletes the downloaded model. */
    fun delete() {
        watcher?.cancel()
        val ids = pending().keys.toLongArray()
        if (ids.isNotEmpty()) downloads.remove(*ids)
        prefs.edit().remove(KEY_PENDING).apply()
        dir.deleteRecursively()
        _state.value = ModelState.Missing
    }

    private fun initialState(): ModelState = when {
        isVerified() -> ModelState.Ready(dir)
        pending().isNotEmpty() -> ModelState.Downloading(0, SpeechModel.totalBytes, waitingForWifi = false)
        else -> ModelState.Missing
    }

    private fun isVerified(): Boolean =
        runCatching { File(dir, VERIFIED).readText() }.getOrNull() == SpeechModel.ID &&
            SpeechModel.files.all { File(dir, it.name).length() == it.size } &&
            File(dir, Transcriber.BPE_VOCAB).exists()

    /** Download ids still running, each with the model file it writes. */
    private fun pending(): Map<Long, String> =
        prefs.getString(KEY_PENDING, "").orEmpty().split(',').mapNotNull { entry ->
            val (id, name) = entry.split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            id.toLongOrNull()?.let { it to name }
        }.toMap()

    private fun watch() {
        watcher?.cancel()
        watcher = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val pending = pending()
                if (pending.isEmpty()) break
                var done = 0L
                var waiting = false
                var failure: String? = null
                val finished = mutableListOf<Long>()
                for ((id, name) in pending) {
                    downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                        if (!cursor.moveToFirst()) {
                            failure = "The download was cancelled."
                            return@use
                        }
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        done += cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            .coerceAtLeast(0)
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                val part = File(dir, name + PART)
                                val target = File(dir, name)
                                if (part.exists()) {
                                    target.delete()
                                    part.renameTo(target)
                                }
                                finished += id
                            }
                            DownloadManager.STATUS_FAILED -> failure = "The download failed (error $reason)."
                            DownloadManager.STATUS_PAUSED -> waiting = waiting ||
                                reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI ||
                                reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK
                        }
                    }
                    if (failure != null) break
                }
                failure?.let { message ->
                    downloads.remove(*pending.keys.toLongArray())
                    prefs.edit().remove(KEY_PENDING).apply()
                    pending.values.forEach { File(dir, it + PART).delete() }
                    _state.value = ModelState.Failed(message)
                    return@launch
                }
                val complete = SpeechModel.files.filter { it.name !in pending.values }.sumOf { File(dir, it.name).length() }
                if (finished.size == pending.size) {
                    prefs.edit().remove(KEY_PENDING).apply()
                    verify()
                    return@launch
                }
                _state.value = ModelState.Downloading(done + complete, SpeechModel.totalBytes, waiting)
                delay(POLL_MS)
            }
        }
    }

    private fun verify() {
        _state.value = ModelState.Checking
        val bad = SpeechModel.files.filter { file ->
            val f = File(dir, file.name)
            f.length() != file.size || sha256(f) != file.sha256
        }
        if (bad.isNotEmpty()) {
            bad.forEach { File(dir, it.name).delete() }
            _state.value = ModelState.Failed("The download was damaged. Try again.")
            return
        }
        File(dir, Transcriber.BPE_VOCAB).writeText(bpeVocab(File(dir, "tokens.txt").readText()))
        File(dir, VERIFIED).writeText(SpeechModel.ID)
        _state.value = ModelState.Ready(dir)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MODELS = "models"
        const val PART = ".part"
        const val VERIFIED = "verified"
        const val KEY_PENDING = "pending"
        const val POLL_MS = 500L
    }
}
