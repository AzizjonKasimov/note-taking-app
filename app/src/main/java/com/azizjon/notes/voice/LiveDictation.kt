package com.azizjon.notes.voice

import android.content.res.AssetManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hears a dictation while it is being recorded: the recorder hands over each buffer, the speech
 * detector finds the pauses, and every finished piece of about 8-20 s is turned into text in the
 * background. After Stop only the last piece is left, so the wait no longer grows with the length
 * of the note. The WAV file stays the safety net: if anything here fails, the controller hears the
 * whole file again.
 */
internal class LiveDictation(
    assets: AssetManager,
    private val transcriber: Transcriber,
    private val model: File,
    private val hotwords: File?,
    scope: CoroutineScope,
) {
    private val lock = Any()
    private var audio = FloatArray(SAMPLE_RATE * 60)
    private var size = 0
    private val detector = SpeechDetector(assets)
    private var lastCut = 0
    private val queue = Channel<IntRange>(Channel.UNLIMITED)
    private val texts = mutableListOf<String>()
    private var queued = 0
    private var released = false

    @Volatile
    private var failure: Throwable? = null

    @Volatile
    var heard = 0
        private set

    private val worker = scope.launch(Dispatchers.Default) {
        try {
            for (range in queue) {
                val piece = synchronized(lock) { audio.copyOfRange(range.first, range.last + 1) }
                val text = transcriber.hear(piece, model, hotwords)
                synchronized(lock) { texts += text }
                heard++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure = e
        }
    }

    /** Takes the recorder's next buffer (called on the recording thread). */
    fun accept(buffer: ShortArray, count: Int) {
        val samples = FloatArray(count) { buffer[it] / 32768f }
        synchronized(lock) {
            if (released) return // a buffer read just after a cancel
            if (size + count > audio.size) audio = audio.copyOf(maxOf(audio.size * 2, size + count))
            samples.copyInto(audio, size)
            size += count
            detector.accept(samples)
            liveCut(detector.speech, lastCut, size)?.let { cut ->
                queue.trySend(lastCut until cut)
                queued++
                lastCut = cut
            }
        }
    }

    /**
     * After the recorder has stopped: hears what is left and returns the whole text. Throws if any
     * piece failed, so the caller can fall back to the WAV file.
     */
    suspend fun finish(onProgress: (done: Int, total: Int) -> Unit): String {
        val rest = synchronized(lock) {
            detector.flush()
            val open = detector.speech
                .filter { it.last >= lastCut }
                .map { (maxOf(it.first, lastCut) - lastCut)..(it.last - lastCut) }
            released = true
            detector.release()
            pieces(open, size - lastCut).map { (it.first + lastCut)..(it.last + lastCut) }
        }
        rest.forEach { queue.send(it) }
        queue.close()
        val total = queued + rest.size
        while (!worker.isCompleted) {
            onProgress(heard, total)
            delay(PROGRESS_MS)
        }
        failure?.let { throw it }
        onProgress(total, total)
        return synchronized(lock) { joinPieces(texts) }
    }

    /** Seconds recorded so far. */
    val seconds: Double
        get() = synchronized(lock) { size / SAMPLE_RATE.toDouble() }

    /** Gives up: stops hearing and frees the detector. */
    fun cancel() {
        queue.close()
        worker.cancel()
        synchronized(lock) {
            if (!released) {
                released = true
                detector.release()
            }
        }
    }

    private companion object {
        const val PROGRESS_MS = 100L
    }
}
