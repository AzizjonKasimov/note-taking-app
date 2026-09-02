package com.azizjon.notes.ui

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartTypingTest {
    @Test
    fun `normalizes line endings and visual nested bullets`() {
        val pasted = "• Parent\r\n◦ Child\r▪ Grandchild"

        assertEquals(
            "- Parent\n    - Child\n        - Grandchild",
            prepareMarkdownForPaste(pasted),
        )
    }

    @Test
    fun `preserves explicit indentation and normalizes parenthesized numbers`() {
        val pasted = "  ◦ Child\n\t2) Second"

        assertEquals(
            "  - Child\n    2. Second",
            prepareMarkdownForPaste(pasted),
        )
    }

    @Test
    fun `detects markdown pasted at caret`() {
        val paste = detectMarkdownPaste(
            beforeText = "Before after",
            afterText = "Before **bold**after",
            replacedRanges = listOf(TextRange(7)),
            clipboardText = "**bold**",
        )

        assertNotNull(paste)
        assertEquals(TextRange(7), paste?.replacedRange)
    }

    @Test
    fun `detects markdown replacing selected text`() {
        val paste = detectMarkdownPaste(
            beforeText = "Replace this text",
            afterText = "Replace # Heading",
            replacedRanges = listOf(TextRange(8, 17)),
            clipboardText = "# Heading",
        )

        assertNotNull(paste)
        assertEquals(TextRange(8, 17), paste?.replacedRange)
    }

    @Test
    fun `detects multiline paste when editor represents paragraph breaks as spaces`() {
        val paste = detectMarkdownPaste(
            beforeText = "",
            afterText = "- Parent - Child",
            replacedRanges = listOf(TextRange.Zero),
            clipboardText = "- Parent\r\n- Child",
        )

        assertNotNull(paste)
        assertEquals("- Parent\n- Child", paste?.clipboardText)
    }

    @Test
    fun `ignores ordinary paste and clipboard mismatch`() {
        assertNull(
            detectMarkdownPaste(
                beforeText = "Hello ",
                afterText = "Hello ordinary text",
                replacedRanges = listOf(TextRange(6)),
                clipboardText = "ordinary text",
            ),
        )
        assertNull(
            detectMarkdownPaste(
                beforeText = "Hello ",
                afterText = "Hello **bold**",
                replacedRanges = listOf(TextRange(6)),
                clipboardText = "different clipboard",
            ),
        )
    }

    @Test
    fun `formats paste and preserves undo back to original content`() {
        val state = RichTextState()
        state.setMarkdown("Keep old ending")
        state.selection = TextRange(5, 8)
        state.replaceSelectedText("• Parent\n◦ Child")
        val paste = DetectedMarkdownPaste(
            replacedRange = TextRange(5, 8),
            clipboardText = "• Parent\n◦ Child",
            markdown = "- Parent\n    - Child",
        )

        applyMarkdownPaste(state, paste)

        assertEquals("Keep\n\n- Parent\n    - Child\nending", state.toMarkdown())
        val undoStates = buildList {
            repeat(8) {
                if (!state.history.canUndo) return@repeat
                state.history.undo()
                add(state.toMarkdown())
            }
        }
        assertTrue(undoStates.contains("Keep old ending"))
    }

    @Test
    fun `formats inline markdown without disturbing surrounding text`() {
        val state = RichTextState()
        state.setMarkdown("Before after")
        state.selection = TextRange(7)
        state.addTextAfterSelection("**bold** ")

        applyMarkdownPaste(
            state,
            DetectedMarkdownPaste(
                replacedRange = TextRange(7),
                clipboardText = "**bold** ",
                markdown = "**bold** ",
            ),
        )

        assertEquals("Before **bold** after", state.toMarkdown())
    }

    @Test
    fun `nested list controls change marker level and round trip markdown`() {
        val state = RichTextState()
        state.setMarkdown("- Parent\n- Child")
        val childStart = state.annotatedString.text.indexOf("Child")
        state.selection = TextRange(childStart)

        assertTrue(state.canIncreaseListLevel)
        state.increaseListLevel()
        assertTrue(state.annotatedString.text.contains("◦ Child"))
        assertEquals("- Parent\n    - Child", state.toMarkdown())

        assertTrue(state.canDecreaseListLevel)
        state.decreaseListLevel()
        assertEquals("- Parent\n- Child", state.toMarkdown())
    }

    @Test
    fun `auto-links bare url without nesting an existing markdown link`() {
        assertEquals(
            "[OpenAI](https://openai.com) and [https://example.com](https://example.com)",
            prepareMarkdownForPaste("[OpenAI](https://openai.com) and https://example.com"),
        )
    }
}
