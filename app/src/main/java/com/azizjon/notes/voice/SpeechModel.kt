package com.azizjon.notes.voice

/**
 * The speech model downloaded on first use: NVIDIA's Parakeet TDT 0.6B v2 (English, CC-BY-4.0),
 * quantized to int8 and exported for sherpa-onnx. The URLs are pinned to one revision and every
 * file is checked against its SHA-256, so the phone runs exactly the files that were measured.
 */
internal object SpeechModel {
    const val ID = "parakeet-tdt-0.6b-v2-int8"
    private const val BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/" +
        "resolve/84b478d541cdbf0c296a7ab5f1e20a6f8f998114/"

    val files = listOf(
        ModelFile("encoder.int8.onnx", 652_282_309L, "92a10bd9c23936ea0566c29f963bc57c2a5df4261b6a8921c39abea66557ca8b"),
        ModelFile("decoder.int8.onnx", 7_257_753L, "4d097bae3bac5c96018d5293ba39f1b657296102b142affda40378f38b020f41"),
        ModelFile("joiner.int8.onnx", 1_739_080L, "77a29f3e5ac7d37074e0af465391b80f314015d5b97f0f90c6c7767f1d56f428"),
        ModelFile("tokens.txt", 9_384L, "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"),
    )

    val totalBytes: Long = files.sumOf { it.size }

    fun url(file: ModelFile): String = BASE + file.name
}

internal data class ModelFile(val name: String, val size: Long, val sha256: String)

/**
 * The model's tokens as the SentencePiece vocabulary sherpa-onnx needs to boost names
 * (`bpe.vocab`, which the model package does not include). Parakeet's tokenizer is BPE, whose
 * piece ids follow the order its merges were learned, so a score of minus the id ranks the merges
 * the way SentencePiece does. Measured on the owner's recordings: names helped as expected.
 */
internal fun bpeVocab(tokens: String): String = buildString {
    tokens.lineSequence().forEach { line ->
        val parts = line.trim().split(' ')
        if (parts.size == 2 && parts[0] != "<blk>") {
            val id = parts[1].toIntOrNull() ?: return@forEach
            append(parts[0]).append('\t').append(-id).append('\n')
        }
    }
}
