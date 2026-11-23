package com.azizjon.notes.voice

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Turns speech into text on the phone with sherpa-onnx's Parakeet. Recordings are cut at pauses
 * into pieces of at most 20 s (see [pieces]) and each piece is heard on its own. The model takes
 * about a gigabyte of memory, so [VoiceController] frees it a while after the last dictation.
 */
internal class Transcriber(
    private val assets: AssetManager,
    private val threads: Int = DEFAULT_THREADS,
) {
    private val lock = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var loadedFor: String? = null

    /** Loads the model ahead of time, such as while the user is still talking. */
    suspend fun prepare(model: File, hotwords: File?) = withContext(Dispatchers.Default) {
        lock.withLock { load(model, hotwords) }
    }

    /** Hears one piece of speech (at most about 20 s). */
    suspend fun hear(piece: FloatArray, model: File, hotwords: File?): String = withContext(Dispatchers.Default) {
        lock.withLock {
            val recognizer = load(model, hotwords)
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(piece, SAMPLE_RATE)
                recognizer.decode(stream)
                recognizer.getResult(stream).text
            } finally {
                stream.release()
            }
        }
    }

    /** Hears a whole recording at once, such as a kept recording turned into text again. */
    suspend fun transcribe(
        audio: FloatArray,
        model: File,
        hotwords: File?,
        onProgress: (done: Int, total: Int) -> Unit,
    ): String = withContext(Dispatchers.Default) {
        val detector = SpeechDetector(assets)
        val speech = try {
            detector.accept(audio)
            detector.flush()
            detector.speech.toList()
        } finally {
            detector.release()
        }
        val parts = pieces(speech, audio.size)
        onProgress(0, parts.size)
        val texts = ArrayList<String>(parts.size)
        parts.forEachIndexed { i, range ->
            ensureActive()
            texts += hear(audio.copyOfRange(range.first, range.last + 1), model, hotwords)
            onProgress(i + 1, parts.size)
        }
        joinPieces(texts)
    }

    /** Frees the model's memory; the next dictation loads it again. */
    suspend fun release() = lock.withLock {
        recognizer?.release()
        recognizer = null
        loadedFor = null
    }

    private fun load(model: File, hotwords: File?): OfflineRecognizer {
        val key = model.path + "|" + (hotwords?.let { "${it.path}@${it.lastModified()}" } ?: "")
        recognizer?.let { current ->
            if (loadedFor == key) return current
            current.release()
            recognizer = null
        }
        val names = hotwords != null
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(model, "encoder.int8.onnx").path,
                    decoder = File(model, "decoder.int8.onnx").path,
                    joiner = File(model, "joiner.int8.onnx").path,
                ),
                tokens = File(model, "tokens.txt").path,
                numThreads = threads,
                modelType = "nemo_transducer",
                modelingUnit = if (names) "bpe" else "",
                bpeVocab = if (names) File(model, BPE_VOCAB).path else "",
            ),
            // Names need beam search; without them greedy decoding scored the same and is faster.
            decodingMethod = if (names) "modified_beam_search" else "greedy_search",
            hotwordsFile = hotwords?.path ?: "",
            hotwordsScore = 1.5f,
        )
        return OfflineRecognizer(null, config).also {
            recognizer = it
            loadedFor = key
        }
    }

    companion object {
        const val BPE_VOCAB = "bpe.vocab"
        val DEFAULT_THREADS = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }
}

/**
 * Silero VAD over a stream of samples given in any amounts: [speech] collects the stretches of
 * speech found so far, in samples from the start of the stream. Not thread-safe; [release] when done.
 */
internal class SpeechDetector(assets: AssetManager) {
    private val vad = Vad(
        assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                minSilenceDuration = 0.3f,
                // Speech longer than this is split by the detector at its quietest point.
                maxSpeechDuration = MAX_PIECE_SAMPLES / SAMPLE_RATE.toFloat(),
            ),
            sampleRate = SAMPLE_RATE,
        ),
    )
    private val window = FloatArray(WINDOW)
    private var filled = 0
    val speech = mutableListOf<IntRange>()

    fun accept(samples: FloatArray, count: Int = samples.size) {
        for (i in 0 until count) {
            window[filled++] = samples[i]
            if (filled == WINDOW) {
                vad.acceptWaveform(window.copyOf())
                filled = 0
                drain()
            }
        }
    }

    /** Ends the stream: speech still open is closed and collected. */
    fun flush() {
        if (filled > 0) {
            vad.acceptWaveform(window.copyOf(filled))
            filled = 0
        }
        vad.flush()
        drain()
    }

    fun release() = vad.release()

    private fun drain() {
        while (!vad.empty()) {
            val segment = vad.front()
            val start = segment.start.toInt()
            speech += start until start + segment.samples.size
            vad.pop()
        }
    }

    private companion object {
        const val WINDOW = 512
    }
}
