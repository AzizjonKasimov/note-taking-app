package com.azizjon.notes.life

/**
 * A note's text as the editor shows it: a title field for a first-line `# ` heading, and the rest.
 * [prefix] keeps the blank lines between them, so splitting and joining an untouched note gives
 * back exactly the same text.
 */
data class TitledText(val title: String, val prefix: String, val body: String) {
    fun join(): String = joinTitle(title, prefix, body)
}

private val HEADING = Regex("""^#{1,6}\s+(.*?)\s*#*\s*$""")
private val MD_LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
private val MD_LINE_PREFIX = Regex("""(?m)^\s{0,3}(#{1,6}\s+|>\s+|[-*+]\s+(\[[ xX]]\s+)?|\d+\.\s+)""")
private val MD_EMPHASIS = Regex("""[*_`~]""")
private val HTML_COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

/** The note's title: its first heading (after any front matter), else its file name. */
fun noteTitle(path: String, text: String): String {
    for (line in contentLines(text).take(40)) {
        val m = HEADING.matchEntire(line.trimEnd()) ?: continue
        val title = MD_EMPHASIS.replace(MD_LINK.replace(m.groupValues[1]) { it.groupValues[1] }, "").trim()
        if (title.isNotEmpty()) return title
    }
    return path.substringAfterLast('/').removeSuffix(".md").removeSuffix(".MD")
}

/** A short plain-text preview: the first lines after the title, without Markdown marks. */
fun notePreview(text: String, maxChars: Int = 160): String {
    val sb = StringBuilder()
    var skippedTitle = false
    for (line in contentLines(HTML_COMMENT.replace(text, ""))) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed == "---") continue
        if (!skippedTitle && HEADING.matches(trimmed)) {
            skippedTitle = true
            continue
        }
        skippedTitle = true
        val plain = MD_EMPHASIS.replace(MD_LINE_PREFIX.replace(MD_LINK.replace(trimmed) { it.groupValues[1] }, ""), "").trim()
        if (plain.isEmpty()) continue
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(plain)
        if (sb.length >= maxChars) break
    }
    return if (sb.length > maxChars) sb.substring(0, maxChars).trimEnd() + "…" else sb.toString()
}

/** Lines after a leading YAML front matter block, if the file has one. */
private fun contentLines(text: String): Sequence<String> {
    val lines = text.lineSequence()
    if (!text.startsWith("---")) return lines
    var closed = false
    var index = 0
    return lines.dropWhile { line ->
        val inFront = !closed
        if (index++ > 0 && line.trim() == "---") closed = true
        inFront
    }
}

/** What a new note puts between its title and its text. */
const val NEW_NOTE_PREFIX = "\n\n"

/**
 * Splits a note whose first line is a `# ` heading; null for any other note (front matter, no
 * title). [TitledText.prefix] holds the line breaks after the title, the first one included.
 */
fun splitTitle(text: String): TitledText? {
    if (!text.startsWith("# ")) return null
    val firstEnd = text.indexOf('\n')
    if (firstEnd < 0) return text.removePrefix("# ").takeIf { it.isNotBlank() }?.let { TitledText(it, "", "") }
    if (text[firstEnd - 1] == '\r') return null // Windows line endings: leave the note to plain editing
    val title = text.substring(2, firstEnd)
    if (title.isBlank()) return null
    val rest = text.substring(firstEnd)
    val body = rest.trimStart('\n')
    return TitledText(title, prefix = rest.substring(0, rest.length - body.length), body = body)
}

/** The inverse of [splitTitle]. A blank title drops the heading line. */
fun joinTitle(title: String, prefix: String, body: String): String {
    val oneLine = title.replace('\n', ' ').replace('\r', ' ')
    if (oneLine.isBlank()) return body
    val separator = if (body.isNotEmpty() && !prefix.contains('\n')) NEW_NOTE_PREFIX else prefix
    return "# $oneLine$separator$body"
}
