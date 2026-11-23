package com.azizjon.notes.life

import android.content.Context
import com.azizjon.notes.data.Notebook
import com.azizjon.notes.data.NotebookAppearance
import com.azizjon.notes.data.withAppearance

/**
 * How each folder looks in the app (its marker: initial, color, emoji, or photo), kept on the
 * phone only. Each folder gets a lasting number, which the photo store uses as its key; numbers
 * start at 1000 so they never meet the old notebooks' photos.
 */
class FolderAppearanceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The folder as a [Notebook], the model the marker views already draw. */
    fun notebook(path: String, name: String): Notebook {
        val base = Notebook(id = idFor(path), name = name)
        val type = prefs.getString("type/$path", null) ?: return base
        return base.withAppearance(
            NotebookAppearance(
                type = com.azizjon.notes.data.NotebookMarkerType.fromStored(type),
                color = prefs.getInt("color/$path", 0),
                value = prefs.getString("value/$path", "").orEmpty(),
                crop = com.azizjon.notes.data.NormalizedCrop(
                    left = prefs.getFloat("cropLeft/$path", 0f),
                    top = prefs.getFloat("cropTop/$path", 0f),
                    size = prefs.getFloat("cropSize/$path", 1f),
                ),
            ),
        )
    }

    fun update(path: String, appearance: NotebookAppearance) {
        prefs.edit()
            .putString("type/$path", appearance.type.name)
            .putInt("color/$path", appearance.color)
            .putString("value/$path", appearance.value)
            .putFloat("cropLeft/$path", appearance.crop.left)
            .putFloat("cropTop/$path", appearance.crop.top)
            .putFloat("cropSize/$path", appearance.crop.size)
            .apply()
    }

    fun pathFor(id: Long): String? =
        prefs.all.entries.firstOrNull { (key, value) -> key.startsWith("id/") && value == id }?.key?.removePrefix("id/")

    @Synchronized
    private fun idFor(path: String): Long {
        val key = "id/$path"
        if (prefs.contains(key)) return prefs.getLong(key, 0L)
        val next = prefs.getLong(NEXT_ID, FIRST_ID)
        prefs.edit().putLong(key, next).putLong(NEXT_ID, next + 1).commit()
        return next
    }

    companion object {
        const val PREFS_NAME = "folder_appearance"
        private const val NEXT_ID = "next_id"
        private const val FIRST_ID = 1000L
    }
}
