package com.azizjon.notes.life

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.azizjon.notes.ui.isRichSafe
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Counts which notes of the repo cloned on this device the editor would open in rich mode, and
 * logs the others (`adb logcat -s RichSafe:I`). Skipped when no repo is cloned.
 */
@RunWith(AndroidJUnit4::class)
class RichSafeCensusTest {

    @Test
    fun countRichSafeNotes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "life")
        assumeTrue("no repo cloned on this device", File(root, ".git").isDirectory)
        val files = NoteFiles(root)
        val paths = files.listMarkdown().sorted()
        val unsafe = paths.filterNot { path ->
            val text = files.read(path).orEmpty()
            isRichSafe(splitTitle(text)?.body ?: text)
        }
        Log.i(TAG, "${paths.size - unsafe.size} of ${paths.size} notes open in rich mode; Markdown only:")
        unsafe.forEach { path ->
            val text = files.read(path).orEmpty()
            val body = (splitTitle(text)?.body ?: text).trimEnd()
            val back = com.mohamedrejeb.richeditor.model.RichTextState().apply { setMarkdown(body) }.toMarkdown().trimEnd()
            val a = body.lines()
            val b = back.lines()
            val i = (0 until minOf(a.size, b.size)).firstOrNull { a[it] != b[it] } ?: minOf(a.size, b.size)
            Log.i(TAG, "  $path | line ${i + 1}: [${a.getOrNull(i)?.take(70)}] -> [${b.getOrNull(i)?.take(70)}]")
        }
    }

    private companion object {
        const val TAG = "RichSafe"
    }
}
