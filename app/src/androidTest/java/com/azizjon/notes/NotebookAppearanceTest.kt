package com.azizjon.notes

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.azizjon.notes.data.Notebook
import com.azizjon.notes.data.NotebookAppearance
import com.azizjon.notes.data.NotebookMarkerType
import com.azizjon.notes.data.appearance
import com.azizjon.notes.data.automaticMarkerColor
import com.azizjon.notes.data.firstGrapheme
import com.azizjon.notes.data.notebookMarkerInitial
import com.azizjon.notes.data.sanitized
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotebookAppearanceTest {
    @Test
    fun automaticColor_isStableAndWithinPalette() {
        assertEquals(automaticMarkerColor(42), automaticMarkerColor(42))
        assertTrue(automaticMarkerColor(Long.MAX_VALUE) in 0..7)
    }

    @Test
    fun unicodeInitial_andCustomEmoji_keepOneGrapheme() {
        assertEquals("É", notebookMarkerInitial("  éclair"))
        assertEquals("🧑‍💻", firstGrapheme("🧑‍💻 notes"))
        assertEquals(
            "🧑‍💻",
            NotebookAppearance(type = NotebookMarkerType.EMOJI, value = "🧑‍💻extra").sanitized().value,
        )
    }

    @Test
    fun invalidStoredTypeAndPreset_fallBackToAuto() {
        assertEquals(NotebookMarkerType.AUTO, Notebook(markerType = "FUTURE_TYPE").appearance().type)
        assertEquals(
            NotebookMarkerType.AUTO,
            NotebookAppearance(type = NotebookMarkerType.PRESET_PHOTO, value = "missing").sanitized().type,
        )
    }
}
