package com.azizjon.notes.voice

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hears recordings pushed to the debug app's files with the real speech model, on the device
 * itself, and writes each text and its time to bench/result.json, which tools/speech-bench scores.
 * Skipped unless a model and recordings were pushed first (tools/speech-bench/README.md says how).
 */
@RunWith(AndroidJUnit4::class)
class TranscriberDeviceTest {
    @Test
    fun hearsPushedRecordings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Optional: am instrument -e threads 6 -e limit 40 -e names false
        val options = InstrumentationRegistry.getArguments()
        val threads = options.getString("threads")?.toIntOrNull() ?: Transcriber.DEFAULT_THREADS
        val limit = options.getString("limit")?.toIntOrNull() ?: Int.MAX_VALUE
        val useNames = options.getString("names") != "false"
        val base = requireNotNull(context.getExternalFilesDir(null))
        val model = File(base, "models/${SpeechModel.ID}")
        val bench = File(base, "bench")
        // Made by the app, so files adb pushes into them are readable here (folders adb makes are not).
        model.mkdirs()
        bench.mkdirs()
        val recordings = bench.listFiles { f -> f.extension == "wav" }?.sortedBy { it.name }.orEmpty().take(limit)
        assumeTrue(
            "Push the model to $model and 16 kHz recordings to $bench first",
            File(model, "encoder.int8.onnx").exists() && recordings.isNotEmpty(),
        )
        val vocab = File(model, Transcriber.BPE_VOCAB)
        if (!vocab.exists()) vocab.writeText(bpeVocab(File(model, "tokens.txt").readText()))
        val names = File(bench, "names.txt").takeIf { useNames && it.exists() }?.let { parseNames(it.readText()) }.orEmpty()
        val hotwords = if (names.isEmpty()) null else {
            File(context.filesDir, "bench-hotwords.txt").apply { writeText(names.joinToString("\n", postfix = "\n")) }
        }

        val transcriber = Transcriber(context.assets, threads)
        val loading = SystemClock.elapsedRealtime()
        transcriber.prepare(model, hotwords)
        val loadSeconds = (SystemClock.elapsedRealtime() - loading) / 1000.0
        val items = JSONObject()
        var audioSeconds = 0.0
        var spentSeconds = 0.0
        for (recording in recordings) {
            val audio = readWav(recording)
            val started = SystemClock.elapsedRealtime()
            val text = transcriber.transcribe(audio, model, hotwords) { _, _ -> }
            val took = (SystemClock.elapsedRealtime() - started) / 1000.0
            val length = audio.size / SAMPLE_RATE.toDouble()
            items.put(
                recording.nameWithoutExtension,
                JSONObject().put("text", text).put("seconds", took).put("audio_seconds", length),
            )
            audioSeconds += length
            spentSeconds += took
        }
        transcriber.release()

        val label = "device-${Build.MODEL}-${threads}t" + if (hotwords != null) "-names" else ""
        val summary = "%s: %d recordings, %.1f s of audio heard in %.1f s (%.3f s per second); model loaded in %.1f s"
            .format(label, recordings.size, audioSeconds, spentSeconds, spentSeconds / audioSeconds, loadSeconds)
        File(bench, "result.json").writeText(
            JSONObject()
                .put("engine", label)
                .put("summary", summary)
                .put("items", items)
                .toString(1),
        )
        Log.i("TranscriberDeviceTest", summary)
        assertEquals(recordings.size, items.length())
    }

    /**
     * Plays about a minute of pushed recordings, joined with short pauses, into [LiveDictation] at
     * the pace of a real recording, and logs how long the text takes after the end, beside the
     * time hearing the whole recording at once takes.
     */
    @Test
    fun hearsWhileRecording() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = requireNotNull(context.getExternalFilesDir(null))
        val model = File(base, "models/${SpeechModel.ID}")
        val recordings = File(base, "bench").listFiles { f -> f.extension == "wav" }?.sortedBy { it.name }.orEmpty()
        assumeTrue(File(model, "encoder.int8.onnx").exists() && recordings.size >= 15)
        val pause = FloatArray(SAMPLE_RATE * 7 / 10)
        val audio = recordings.take(15).flatMap { listOf(readWav(it), pause) }
            .fold(FloatArray(0)) { all, part -> all + part }

        val transcriber = Transcriber(context.assets)
        transcriber.prepare(model, null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val live = LiveDictation(context.assets, transcriber, model, null, scope)
        val buffer = ShortArray(SAMPLE_RATE / 10)
        var i = 0
        while (i < audio.size) {
            val n = minOf(buffer.size, audio.size - i)
            for (k in 0 until n) buffer[k] = (audio[i + k] * 32767f).toInt().toShort()
            live.accept(buffer, n)
            i += n
            delay(100) // the pace of the microphone
        }
        val ended = SystemClock.elapsedRealtime()
        val liveText = live.finish { _, _ -> }
        val liveWait = (SystemClock.elapsedRealtime() - ended) / 1000.0

        val started = SystemClock.elapsedRealtime()
        val wholeText = transcriber.transcribe(audio, model, null) { _, _ -> }
        val wholeTime = (SystemClock.elapsedRealtime() - started) / 1000.0
        transcriber.release()

        val words = liveText.split(' ').size
        Log.i(
            "TranscriberDeviceTest",
            "Live on %s: %.1f s of speech, text ready %.1f s after the end (%d words); hearing it all at once took %.1f s (%d words)"
                .format(Build.MODEL, audio.size / SAMPLE_RATE.toDouble(), liveWait, words, wholeTime, wholeText.split(' ').size),
        )
        Log.i("TranscriberDeviceTest", "Live text: $liveText")
        Log.i("TranscriberDeviceTest", "Whole text: $wholeText")
        assertTrue(words > 50)
    }
}
