package com.azizjon.notes.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Records the microphone at 16 kHz mono into a WAV file, the format the model hears. */
internal class VoiceRecorder {
    /**
     * Records into [file] until the calling coroutine is cancelled, handing each buffer to [onAudio]
     * and calling [onProgress] about ten times a second with the samples so far and the loudness
     * (0..1). The file is complete when this returns or throws, so a stop keeps every word said so
     * far. Needs RECORD_AUDIO.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(
        file: File,
        onAudio: (buffer: ShortArray, count: Int) -> Unit,
        onProgress: (samples: Long, level: Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            // Tuned for speech recognition: no automatic gain changes between words.
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, SAMPLE_RATE * 2),
        )
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not be opened." }
            val buffer = ShortArray(SAMPLE_RATE / 10)
            WavWriter(file).use { wav ->
                record.startRecording()
                while (isActive) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n < 0) error("The microphone stopped working (error $n).")
                    if (n == 0) continue
                    wav.write(buffer, n)
                    onAudio(buffer, n)
                    onProgress(wav.samples, level(buffer, n))
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    private fun level(buffer: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) {
            val s = buffer[i] / 32768.0
            sum += s * s
        }
        // The square root again spreads quiet speech over more of the meter.
        return sqrt(sqrt(sum / count)).toFloat().coerceIn(0f, 1f)
    }
}
