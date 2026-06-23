package com.azizjon.notes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.model.RichTextState

private val ORDERED_PREFIX = Regex("""^\d+\.\s$""")
private val UNORDERED_PREFIX = Regex("""^[-*]\s$""")

/**
 * Markdown autoformat-on-type, like Docs/Notion: when the user types a list marker at the very start
 * of a line ("1. " → ordered, "- " or "* " → bullets), the marker is removed and a real list is
 * started instead. Pasted text isn't affected (the whole line lands at once, so the prefix-only
 * match never fires), and the post-edit state is already a list, so it can't loop.
 */
@Composable
fun MarkdownShortcuts(state: RichTextState) {
    LaunchedEffect(state) {
        snapshotFlow { state.annotatedString.text to state.selection }
            .collect { (text, selection) ->
                if (!selection.collapsed) return@collect
                if (state.isOrderedList || state.isUnorderedList) return@collect
                val cursor = selection.min
                if (cursor <= 0 || cursor > text.length) return@collect

                val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
                val prefix = text.substring(lineStart, cursor)
                when {
                    ORDERED_PREFIX.matches(prefix) -> {
                        state.removeTextRange(TextRange(lineStart, cursor))
                        state.toggleOrderedList()
                    }
                    UNORDERED_PREFIX.matches(prefix) -> {
                        state.removeTextRange(TextRange(lineStart, cursor))
                        state.toggleUnorderedList()
                    }
                }
            }
    }
}
