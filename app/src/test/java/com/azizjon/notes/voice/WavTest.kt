package com.azizjon.notes.voice

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val samples = shortArrayOf(0, 16384, -16384, 32767, -32768)

    @Test
    fun whatIsWrittenReadsBack() {
        val file = folder.newFile("a.wav")
        WavWriter(file).use { it.write(samples, samples.size) }
        assertEquals(44L + samples.size * 2, file.length())
        assertArrayEquals(samples.map { it / 32768f }.toFloatArray(), readWav(file), 0f)
    }

    @Test
    fun aRecordingCutOffBeforeItsHeaderWasFinishedStillReads() {
        val file = folder.newFile("b.wav")
        WavWriter(file).use { it.write(samples, samples.size) }
        // The app was killed while recording: the header still says there is no audio.
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(40)
            raf.write(byteArrayOf(0, 0, 0, 0))
        }
        assertEquals(samples.size, readWav(file).size)
    }

    @Test
    fun writesInSeveralBuffers() {
        val file = File(folder.root, "c.wav")
        WavWriter(file).use { wav ->
            wav.write(samples, 2)
            wav.write(samples, samples.size)
            assertEquals(7L, wav.samples)
        }
        assertEquals(7, readWav(file).size)
    }
}
