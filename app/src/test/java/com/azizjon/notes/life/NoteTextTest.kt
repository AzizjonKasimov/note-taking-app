package com.azizjon.notes.life

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class NoteTextTest {

    @Test
    fun splitAndJoinGiveBackTheSameText() {
        val samples = listOf(
            "# Title\n\nBody\n",
            "# Title\nBody",
            "# Title\n",
            "# Title",
            "# Title\n\n\n- a\n- b\n",
        )
        for (text in samples) {
            val split = splitTitle(text)!!
            assertEquals(text, split.join())
        }
    }

    @Test
    fun notesWithoutATitleLineAreNotSplit() {
        assertNull(splitTitle("---\nname: x\n---\n# Title\n"))
        assertNull(splitTitle("Just text"))
        assertNull(splitTitle("## Second level\n"))
        assertNull(splitTitle("# Title\r\nWindows"))
        assertNull(splitTitle("# \n\nText"))
    }

    @Test
    fun aTitleAddedToAnEmptyNoteGetsABlankLine() {
        assertEquals("# Groceries\n\nmilk", joinTitle("Groceries", "", "milk"))
        assertEquals("# Groceries\n\nmilk", joinTitle("Groceries", NEW_NOTE_PREFIX, "milk"))
        assertEquals("milk", joinTitle("  ", "\n\n", "milk"))
    }

    @Test
    fun titlesComeFromTheFirstHeadingOrTheFileName() {
        assertEquals("Ideas", noteTitle("ideas/README.md", "# Ideas\n\ntext"))
        assertEquals("Plan", noteTitle("x.md", "---\ntitle: y\n---\n\n## **Plan**\n"))
        assertEquals("2026-11-23-1530", noteTitle("inbox/2026-11-23-1530.md", "no heading here"))
    }

    @Test
    fun previewSkipsTheTitleAndMarkdownMarks() {
        assertEquals("one two link", notePreview("# T\n\n- **one**\n- two\n\n<!-- hidden -->\n[link](https://x)\n"))
    }

    @Test
    fun editorSavesMergeWithChangesThatArrivedMeanwhile() {
        val root = Files.createTempDirectory("notes").toFile()
        try {
            val files = NoteFiles(root)
            files.write("a.md", "one\ntwo\nthree\n")
            val base = files.read("a.md")!!
            // A sync brings an edit from the PC while the note is open.
            files.write("a.md", "one\ntwo\nthree (pc)\n")
            val saved = files.writeFromEditor("a.md", "one (phone)\ntwo\nthree\n", base)
            assertEquals("one (phone)\ntwo\nthree (pc)\n", saved.diskText)
            assertEquals(saved.diskText, files.read("a.md"))
            // A note deleted elsewhere while open is written again.
            files.delete("a.md")
            files.writeFromEditor("a.md", "kept\n", saved.diskText)
            assertEquals("kept\n", files.read("a.md"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun pathsStayInsideTheRepo() {
        for (bad in listOf("../a.md", ".git/config", "/abs.md", "a//b.md", "a\\b.md", "")) {
            assert(!NoteFiles.isSafePath(bad)) { bad }
        }
        assertEquals("gift-ideas-for-may", NoteFiles.slugify("Gift ideas for May!"))
        assertEquals("대전-카페", NoteFiles.slugify("대전 카페"))
    }
}
