package com.azizjon.notes.life

import com.azizjon.notes.data.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AppNotesMoveTest {
    private val movedAt = 1_795_429_692_763L

    @Test
    fun onlyNotesWrittenOrChangedAfterTheHandMoveAreMoved() {
        val notes = listOf(
            Note(id = 13, title = "Moved by hand", content = "x", updatedAt = movedAt - 1),
            Note(id = 13 + 1, title = "Edited later", content = "x", updatedAt = movedAt + 60_000),
            Note(id = 32, title = "Written later", content = "y", updatedAt = movedAt + 1),
            Note(id = 33, title = "", content = "", updatedAt = movedAt + 1),
            Note(id = 34, title = "Trashed", content = "z", updatedAt = movedAt + 1, deletedAt = movedAt + 2),
        )
        assertEquals(listOf(14L, 32L), AppNotesMove.toMove(notes).map { it.id })
    }

    @Test
    fun movedNotesLandInTheInboxWithTheirTitleAndOrigin() {
        val root = Files.createTempDirectory("move").toFile()
        try {
            val files = NoteFiles(root)
            val paths = AppNotesMove.write(
                files,
                listOf(
                    Note(id = 32, title = "Call the clinic", content = "- ask about the scan", notebookId = 4, createdAt = movedAt),
                    Note(id = 35, title = "", content = "untitled thought", notebookId = 1, createdAt = movedAt),
                ),
                mapOf(4L to "Life"),
            )
            assertEquals("inbox/call-the-clinic.md", paths[0])
            assertTrue(paths[1].startsWith("inbox/") && paths[1].endsWith(".md"))
            val text = files.read(paths[0])!!
            assertTrue(text, text.startsWith("# Call the clinic\n\n- ask about the scan\n\n<!-- From the Notes app's \"Life\" notebook"))
            assertTrue(files.read(paths[1])!!.startsWith("untitled thought\n\n<!-- From the Notes app's \"Unfiled\" notebook"))
        } finally {
            root.deleteRecursively()
        }
    }
}
