package com.azizjon.notes.voice

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.ContextCompat

sealed interface VoiceState {
    data object Idle : VoiceState
    data class Recording(val millis: Long, val level: Float) : VoiceState
    data class Transcribing(val done: Int, val total: Int) : VoiceState
    data class Done(val text: String) : VoiceState
    data class Failed(val message: String, val canRetry: Boolean) : VoiceState
}

/**
 * One dictation at a time, for the whole app. It records into a WAV file under the app's files and
 * hears the speech while it is being recorded ([LiveDictation]), so the text follows soon after
 * Stop. The recording is kept until its text has been used, so a crash or a failure loses nothing:
 * the whole file is heard again, or [leftover] finds it on the next dictation.
 */
class VoiceController internal constructor(
    private val app: Application,
    internal val models: ModelStore,
    private val scope: CoroutineScope,
) {
    private val transcriber = Transcriber(app.assets)
    private val recorder = VoiceRecorder()
    private val recordings = File(app.filesDir, "recordings")
    private val prefs = app.getSharedPreferences("voice", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()
    private var recording: Job? = null
    private var transcribing: Job? = null
    private var unloading: Job? = null
    private var live: LiveDictation? = null
    private var current: File? = null

    /** The names to spell right, one per line, as typed in Settings. */
    var namesText: String
        get() = prefs.getString(KEY_NAMES, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_NAMES, value).apply()
        }

    /** A recording whose text was never used, from a run that was closed or failed, newest first. */
    fun leftover(): File? =
        if (_state.value != VoiceState.Idle) null
        else recordings.listFiles { f -> f.extension == "wav" && f.length() > 44 }?.maxByOrNull { it.lastModified() }

    fun start() {
        if (_state.value != VoiceState.Idle) return
        val model = (models.state.value as? ModelState.Ready)?.dir ?: return
        // Android closes the app when a microphone service starts without the permission, so a mic
        // button that forgot to ask (rememberMicStarter) gets a message instead.
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = VoiceState.Failed("Notes needs the microphone for this. Tap the mic again and allow it.", canRetry = false)
            return
        }
        unloading?.cancel()
        recordings.mkdirs()
        val file = File(recordings, "dictation-${System.currentTimeMillis()}.wav")
        current = file
        _state.value = VoiceState.Recording(0, 0f)
        RecordingService.start(app)
        recording = scope.launch {
            val hotwords = hotwords()
            // Load the model while the user talks; pieces heard meanwhile wait for it.
            scope.launch {
                val started = SystemClock.elapsedRealtime()
                runCatching { transcriber.prepare(model, hotwords) }
                    .onSuccess { Log.i(TAG, "Model ready in %.1f s".format((SystemClock.elapsedRealtime() - started) / 1000.0)) }
                    .onFailure { Log.w(TAG, "Model did not load", it) }
            }
            val dictation = LiveDictation(app.assets, transcriber, model, hotwords, scope)
            live = dictation
            try {
                recorder.record(file, dictation::accept) { samples, level ->
                    _state.value = VoiceState.Recording(samples * 1000 / SAMPLE_RATE, level)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dictation.cancel()
                live = null
                _state.value = VoiceState.Failed(e.message ?: "The recording stopped.", canRetry = file.length() > 44)
            } finally {
                RecordingService.stop(app)
            }
        }
    }

    /** Ends the recording and turns the rest of it into text. */
    fun stop() {
        val job = recording ?: return
        val file = current ?: return
        recording = null
        transcribing = scope.launch {
            job.cancelAndJoin()
            if (_state.value !is VoiceState.Recording) return@launch
            val dictation = live
            live = null
            if (dictation == null) {
                transcribe(file)
                return@launch
            }
            val stopped = SystemClock.elapsedRealtime()
            _state.value = VoiceState.Transcribing(0, 0)
            val text = try {
                dictation.finish { done, total -> _state.value = VoiceState.Transcribing(done, total) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Hearing while recording failed; hearing the whole file", e)
                null
            }
            if (text == null) {
                transcribe(file)
                return@launch
            }
            Log.i(
                TAG,
                "Heard %.1f s of speech; text ready %.1f s after Stop"
                    .format(dictation.seconds, (SystemClock.elapsedRealtime() - stopped) / 1000.0),
            )
            _state.value = if (text.isBlank()) {
                VoiceState.Failed("No speech was heard in the recording.", canRetry = false)
            } else {
                VoiceState.Done(text)
            }
        }
    }

    /** Turns a kept recording into text: the one that failed, or a [leftover]. */
    fun retry(file: File? = current) {
        val target = file ?: return
        if (_state.value is VoiceState.Recording || _state.value is VoiceState.Transcribing) return
        unloading?.cancel()
        current = target
        transcribing = scope.launch { transcribe(target) }
    }

    /**
     * The text was used, or the dictation was given up: deletes the recording. The model stays in
     * memory for a few minutes, so the next dictation does not wait for it to load again.
     */
    fun finish() {
        recording?.cancel()
        recording = null
        transcribing?.cancel()
        transcribing = null
        live?.cancel()
        live = null
        current?.delete()
        current = null
        _state.value = VoiceState.Idle
        unloading?.cancel()
        unloading = scope.launch {
            delay(KEEP_LOADED_MS)
            transcriber.release()
        }
    }

    /** Deletes a leftover recording without turning it into text. */
    fun discard(file: File) {
        file.delete()
    }

    private suspend fun transcribe(file: File) {
        val model = (models.state.value as? ModelState.Ready)?.dir
        if (model == null) {
            _state.value = VoiceState.Failed("The speech model is not on the phone yet.", canRetry = true)
            return
        }
        _state.value = VoiceState.Transcribing(0, 0)
        try {
            val audio = withContext(Dispatchers.IO) { readWav(file) }
            val text = transcriber.transcribe(audio, model, hotwords()) { done, total ->
                _state.value = VoiceState.Transcribing(done, total)
            }
            _state.value = if (text.isBlank()) {
                VoiceState.Failed("No speech was heard in the recording.", canRetry = false)
            } else {
                VoiceState.Done(text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.value = VoiceState.Failed(
                "The recording could not be turned into text (${e.message ?: e.javaClass.simpleName}).",
                canRetry = true,
            )
        }
    }

    /** The names as the hotwords file sherpa-onnx reads, or null when there are none. */
    private suspend fun hotwords(): File? = withContext(Dispatchers.IO) {
        val names = parseNames(namesText)
        val file = File(app.filesDir, "voice/hotwords.txt")
        if (names.isEmpty()) return@withContext null
        val text = names.joinToString("\n", postfix = "\n")
        if (!file.exists() || file.readText() != text) {
            file.parentFile?.mkdirs()
            file.writeText(text)
        }
        file
    }

    private companion object {
        const val TAG = "Voice"
        const val KEY_NAMES = "names"
        const val KEEP_LOADED_MS = 3 * 60 * 1000L
    }
}
