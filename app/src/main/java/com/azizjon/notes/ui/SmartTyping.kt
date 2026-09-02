package com.azizjon.notes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.model.RichTextState

private val ORDERED_PREFIX = Regex("""^\d+\.\s$""")
private val UNORDERED_PREFIX = Regex("""^[-*]\s$""")
private val VISUAL_BULLET_LINE = Regex("""^([ \t]*)([•◦▪])\s+(.*)$""")
private val PAREN_NUMBER_LINE = Regex("""^([ \t]*)(\d+)\)\s+(.*)$""")
private val MARKDOWN_SIGNALS = listOf(
    Regex("""(?m)^[ \t]{0,3}#{1,6}\s+\S"""),
    Regex("""(?m)^[ \t]*(?:[-*+]|\d+\.)\s+\S"""),
    Regex("""\*\*[^*\n]+\*\*"""),
    Regex("""__[^_\n]+__"""),
    Regex("""~~[^~\n]+~~"""),
    Regex("""(?<!\*)\*[^*\n]+\*(?!\*)"""),
    Regex("""(?<!_)_[^_\n]+_(?!_)"""),
    Regex("""!?\[[^\]\n]+]\([^\)\n]+\)"""),
    Regex("""`[^`\n]+`"""),
    Regex("""https?://\S+"""),
)
private val BARE_URL = Regex("""\bhttps?://[^\s)]+""")

internal data class EditorTextSnapshot(
    val text: String,
    val selection: TextRange,
)

internal data class DetectedMarkdownPaste(
    val replacedRange: TextRange,
    val clipboardText: String,
    val markdown: String,
)

/**
 * Markdown autoformat for typed list prefixes and plain-text clipboard pastes. Native rich HTML
 * paste remains untouched unless its inserted plain text exactly matches Markdown on the clipboard.
 */
@Suppress("DEPRECATION")
@Composable
fun MarkdownShortcuts(state: RichTextState) {
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(state, clipboardManager) {
        var previous = EditorTextSnapshot(state.annotatedString.text, state.selection)
        var lastNonCollapsedSelection: TextRange? =
            state.selection.takeUnless { it.collapsed }

        snapshotFlow { EditorTextSnapshot(state.annotatedString.text, state.selection) }
            .collect { current ->
                if (current.text == previous.text) {
                    if (!current.selection.collapsed) {
                        lastNonCollapsedSelection = current.selection
                    }
                    previous = current
                    return@collect
                }

                val candidateRanges = buildList {
                    add(previous.selection)
                    lastNonCollapsedSelection?.let(::add)
                }.distinct()

                val mayBePaste = candidateRanges.any { range ->
                    insertedLength(previous.text, current.text, range) > 1
                }

                val detectedPaste = if (mayBePaste && state.history.canUndo) {
                    detectMarkdownPaste(
                        beforeText = previous.text,
                        afterText = current.text,
                        replacedRanges = candidateRanges,
                        clipboardText = clipboardManager.getText()?.text,
                    )
                } else {
                    null
                }

                if (detectedPaste != null) {
                    applyMarkdownPaste(state, detectedPaste)
                    previous = EditorTextSnapshot(state.annotatedString.text, state.selection)
                    lastNonCollapsedSelection = null
                    return@collect
                }

                applyTypedListShortcut(state, current)
                previous = EditorTextSnapshot(state.annotatedString.text, state.selection)
                lastNonCollapsedSelection = null
            }
    }
}

private fun applyTypedListShortcut(state: RichTextState, snapshot: EditorTextSnapshot) {
    if (!snapshot.selection.collapsed) return
    if (state.isOrderedList || state.isUnorderedList) return
    val cursor = snapshot.selection.min
    if (cursor <= 0 || cursor > snapshot.text.length) return

    val lineStart = snapshot.text.lastIndexOf('\n', cursor - 1) + 1
    val prefix = snapshot.text.substring(lineStart, cursor)
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

internal fun detectMarkdownPaste(
    beforeText: String,
    afterText: String,
    replacedRanges: List<TextRange>,
    clipboardText: String?,
): DetectedMarkdownPaste? {
    val normalizedClipboard = clipboardText?.normalizeLineEndings() ?: return null
    if (normalizedClipboard.length <= 1) return null
    val markdown = prepareMarkdownForPaste(normalizedClipboard)
    if (!looksLikeMarkdown(normalizedClipboard, markdown)) return null

    val insertedRepresentations = listOf(
        normalizedClipboard,
        normalizedClipboard.replace('\n', ' '),
    ).distinct()
    val replacedRange = replacedRanges.firstOrNull { range ->
        range.min >= 0 &&
            range.max <= beforeText.length &&
            insertedRepresentations.any { insertedText ->
                beforeText.replaceRange(range.min, range.max, insertedText) == afterText
            }
    } ?: return null

    return DetectedMarkdownPaste(
        replacedRange = replacedRange,
        clipboardText = normalizedClipboard,
        markdown = markdown,
    )
}

internal fun applyMarkdownPaste(state: RichTextState, paste: DetectedMarkdownPaste) {
    var insertionIndex = paste.replacedRange.min
    state.removeTextRange(
        TextRange(insertionIndex, insertionIndex + paste.clipboardText.length),
    )
    if (containsBlockMarkdown(paste.markdown)) {
        val textWithGap = state.annotatedString.text
        val leftWhitespaceStart = textWithGap
            .substring(0, insertionIndex)
            .indexOfLast { it != ' ' && it != '\t' }
            .plus(1)
        val rightWhitespaceEnd = textWithGap
            .substring(insertionIndex)
            .indexOfFirst { it != ' ' && it != '\t' }
            .let { if (it == -1) textWithGap.length else insertionIndex + it }
        if (rightWhitespaceEnd > insertionIndex) {
            state.removeTextRange(TextRange(insertionIndex, rightWhitespaceEnd))
        }
        if (leftWhitespaceStart < insertionIndex) {
            state.removeTextRange(TextRange(leftWhitespaceStart, insertionIndex))
            insertionIndex = leftWhitespaceStart
        }

        val remainingText = state.annotatedString.text
        val needsLeadingParagraph = insertionIndex > 0
        val needsTrailingParagraph = insertionIndex < remainingText.length
        val separators = buildString {
            if (needsLeadingParagraph) append('\n')
            if (needsTrailingParagraph) append('\n')
        }
        if (separators.isNotEmpty()) {
            state.addTextAtIndex(insertionIndex, separators)
        }
        val markdownPosition = insertionIndex + if (needsLeadingParagraph) 1 else 0
        state.insertMarkdown(paste.markdown, markdownPosition)
    } else {
        val leadingWhitespace = paste.markdown.takeWhile { it == ' ' || it == '\t' }
        val trailingWhitespace = paste.markdown.takeLastWhile { it == ' ' || it == '\t' }
        val markdownCore = paste.markdown
            .drop(leadingWhitespace.length)
            .dropLast(trailingWhitespace.length)
        if (leadingWhitespace.isNotEmpty()) {
            state.addTextAtIndex(insertionIndex, leadingWhitespace)
        }
        val markdownPosition = insertionIndex + leadingWhitespace.length
        val lengthBeforeMarkdown = state.annotatedString.text.length
        state.insertMarkdown(markdownCore, markdownPosition)
        val insertedTextLength = state.annotatedString.text.length - lengthBeforeMarkdown
        if (trailingWhitespace.isNotEmpty()) {
            state.addTextAtIndex(
                markdownPosition + insertedTextLength,
                trailingWhitespace,
            )
        }
    }
}

internal fun prepareMarkdownForPaste(raw: String): String =
    autoLinkify(normalizeVisualListMarkers(raw.normalizeLineEndings()))

internal fun normalizeVisualListMarkers(text: String): String =
    text.lineSequence().joinToString("\n") { line ->
        VISUAL_BULLET_LINE.matchEntire(line)?.let { match ->
            val existingIndent = match.groupValues[1].replace("\t", "    ")
            val marker = match.groupValues[2]
            val inferredIndent = when (marker) {
                "◦" -> "    "
                "▪" -> "        "
                else -> ""
            }
            val indent = existingIndent.ifEmpty { inferredIndent }
            return@joinToString "$indent- ${match.groupValues[3]}"
        }

        PAREN_NUMBER_LINE.matchEntire(line)?.let { match ->
            val indent = match.groupValues[1].replace("\t", "    ")
            return@joinToString "$indent${match.groupValues[2]}. ${match.groupValues[3]}"
        }

        line
    }

private fun looksLikeMarkdown(original: String, prepared: String): Boolean =
    prepared != original.normalizeLineEndings() || MARKDOWN_SIGNALS.any { it.containsMatchIn(prepared) }

private fun containsBlockMarkdown(markdown: String): Boolean =
    Regex("""(?m)^[ \t]*(?:#{1,6}\s+|[-*+]\s+|\d+\.\s+)\S""").containsMatchIn(markdown)

private fun insertedLength(beforeText: String, afterText: String, range: TextRange): Int =
    if (range.min < 0 || range.max > beforeText.length) {
        -1
    } else {
        afterText.length - (beforeText.length - (range.max - range.min))
    }

private fun String.normalizeLineEndings(): String = replace("\r\n", "\n").replace('\r', '\n')

/** Wrap bare URLs as Markdown links while leaving URLs already used as link destinations alone. */
private fun autoLinkify(text: String): String = BARE_URL.replace(text) { match ->
    val prefix = text.substring(0, match.range.first)
    if (prefix.endsWith("](")) match.value else "[${match.value}](${match.value})"
}
