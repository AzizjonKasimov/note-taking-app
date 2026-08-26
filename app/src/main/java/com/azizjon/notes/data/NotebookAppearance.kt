package com.azizjon.notes.data

import android.icu.text.BreakIterator
import java.util.Locale

const val NOTEBOOK_MARKER_COLOR_COUNT = 8

enum class NotebookMarkerType {
    AUTO,
    FOLDER,
    INITIAL,
    EMOJI,
    PRESET_PHOTO,
    CUSTOM_PHOTO;

    companion object {
        fun fromStored(value: String): NotebookMarkerType =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

data class NotebookAppearance(
    val type: NotebookMarkerType = NotebookMarkerType.AUTO,
    val color: Int = 0,
    val value: String = "",
    val crop: NormalizedCrop = NormalizedCrop(),
)

/** A square crop in source-image coordinates; [size] is relative to the shorter source edge. */
data class NormalizedCrop(
    val left: Float = 0f,
    val top: Float = 0f,
    val size: Float = 1f,
)

fun Notebook.appearance(): NotebookAppearance = NotebookAppearance(
    type = NotebookMarkerType.fromStored(markerType),
    color = markerColor.floorMod(NOTEBOOK_MARKER_COLOR_COUNT),
    value = markerValue,
    crop = NormalizedCrop(cropLeft, cropTop, cropSize).sanitized(),
)

fun Notebook.withAppearance(appearance: NotebookAppearance): Notebook {
    val safe = appearance.sanitized()
    return copy(
        markerType = safe.type.name,
        markerColor = safe.color,
        markerValue = safe.value,
        cropLeft = safe.crop.left,
        cropTop = safe.crop.top,
        cropSize = safe.crop.size,
    )
}

fun NotebookAppearance.sanitized(): NotebookAppearance {
    val safeValue = when (type) {
        NotebookMarkerType.EMOJI -> firstGrapheme(value)
        NotebookMarkerType.PRESET_PHOTO -> value.takeIf { it in NOTEBOOK_PHOTO_PRESET_KEYS }.orEmpty()
        else -> value
    }
    val safeType = when {
        type == NotebookMarkerType.EMOJI && safeValue.isBlank() -> NotebookMarkerType.AUTO
        type == NotebookMarkerType.PRESET_PHOTO && safeValue.isBlank() -> NotebookMarkerType.AUTO
        else -> type
    }
    return copy(
        type = safeType,
        color = color.floorMod(NOTEBOOK_MARKER_COLOR_COUNT),
        value = safeValue,
        crop = crop.sanitized(),
    )
}

fun NormalizedCrop.sanitized(): NormalizedCrop = copy(
    left = left.coerceIn(0f, 1f),
    top = top.coerceIn(0f, 1f),
    size = size.coerceIn(0.08f, 1f),
)

fun automaticMarkerColor(notebookId: Long): Int =
    (notebookId xor (notebookId ushr 32)).toInt().floorMod(NOTEBOOK_MARKER_COLOR_COUNT)

fun notebookMarkerInitial(name: String): String = firstGrapheme(name).ifBlank { "?" }.uppercase(Locale.getDefault())

fun firstGrapheme(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    val iterator = BreakIterator.getCharacterInstance(Locale.getDefault())
    iterator.setText(trimmed)
    val end = iterator.next().takeIf { it != BreakIterator.DONE } ?: trimmed.length
    return trimmed.substring(0, end)
}

private fun Int.floorMod(modulus: Int): Int = Math.floorMod(this, modulus)

val NOTEBOOK_PHOTO_PRESET_KEYS: Set<String> = setOf(
    "mountain",
    "forest",
    "ocean",
    "desert",
    "city",
    "architecture",
    "workspace",
    "books",
    "coffee",
    "food",
    "galaxy",
    "abstract",
)
