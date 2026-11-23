package com.azizjon.notes.voice

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val SAMPLE_RATE = 16_000
private const val HEADER_BYTES = 44

/**
 * Writes 16 kHz mono 16-bit PCM to a WAV file as it is recorded; [close] fills in the sizes in the
 * header. A file cut off before [close] (the app was killed) still reads with [readWav].
 */
internal class WavWriter(file: File) : AutoCloseable {
    private val out = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(wavHeader(0))
    }
    private var bytes = ByteBuffer.allocate(0)

    /** Samples written so far. */
    var samples: Long = 0
        private set

    fun write(buffer: ShortArray, count: Int) {
        if (bytes.capacity() < count * 2) bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.clear()
        for (i in 0 until count) bytes.putShort(buffer[i])
        out.write(bytes.array(), 0, count * 2)
        samples += count
    }

    override fun close() {
        out.seek(0)
        out.write(wavHeader(samples * 2))
        out.close()
    }
}

internal fun wavHeader(dataBytes: Long): ByteArray =
    ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt((36 + dataBytes).toInt())
        put("WAVE".toByteArray(Charsets.US_ASCII))
        put("fmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1) // PCM
        putShort(1) // mono
        putInt(SAMPLE_RATE)
        putInt(SAMPLE_RATE * 2)
        putShort(2)
        putShort(16)
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(dataBytes.toInt())
    }.array()

/**
 * Reads a WAV file this app wrote (16 kHz mono 16-bit) as samples in -1..1. A data size left at
 * zero or past the end, from a recording that was never closed, reads everything after the header.
 */
internal fun readWav(file: File): FloatArray {
    val bytes = file.readBytes()
    require(
        bytes.size >= HEADER_BYTES &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE",
    ) { "Not a WAV file" }
    var pos = 12
    var dataStart = -1
    var dataLength = 0
    while (pos + 8 <= bytes.size) {
        val id = String(bytes, pos, 4, Charsets.US_ASCII)
        val length = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (id == "fmt " && pos + 24 <= bytes.size) {
            val fmt = ByteBuffer.wrap(bytes, pos + 8, 16).order(ByteOrder.LITTLE_ENDIAN)
            val encoding = fmt.short.toInt()
            val channels = fmt.short.toInt()
            val rate = fmt.int
            fmt.int // bytes per second
            fmt.short // bytes per sample frame
            val bits = fmt.short.toInt()
            require(encoding == 1 && channels == 1 && rate == SAMPLE_RATE && bits == 16) {
                "Expected 16 kHz mono 16-bit PCM, got encoding $encoding, $channels channels, $rate Hz, $bits bits"
            }
        }
        if (id == "data") {
            dataStart = pos + 8
            dataLength = length
            break
        }
        if (length < 0) break
        pos += 8 + length
    }
    require(dataStart > 0) { "The WAV file has no audio" }
    val available = bytes.size - dataStart
    val count = (if (dataLength in 1..available) dataLength else available) / 2
    val data = ByteBuffer.wrap(bytes, dataStart, count * 2).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(count) { data.short / 32768f }
}
