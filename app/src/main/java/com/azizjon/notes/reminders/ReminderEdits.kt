package com.azizjon.notes.reminders

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** An item as the Today screen showed it: its line number in `upcoming.md`, and the line itself. */
data class ItemRef(val line: Int, val raw: String)

/**
 * Changes to single items of `upcoming.md`, made from the Today screen. Each one finds its item
 * again (the list may have changed since it was shown: a sync, the morning upkeep, an edit on the
 * PC) and changes only that line. Null means the item was not found exactly once, so nothing
 * changes.
 */
object ReminderEdits {
    private val MARK_RE = Regex("""^(\s*-\s*\[)[ xX-](\].*)$""")
    private val DATED_RE = Regex("""^(\s*-\s*\[[ xX-]\]\s*)\d{4}-\d{2}-\d{2}(\s.*)$""")
    private val UNDATED_RE = Regex("""^(\s*-\s*\[[ xX-]\]\s*)(.*)$""")

    fun ref(up: Upcoming, item: Item) = ItemRef(item.line, up.lines[item.line])

    /** Sets the item's box: ' ' (open), 'x' (done), or '-' (skipped). */
    fun setMark(text: String, ref: ItemRef, mark: Char): String? = change(text, ref) { line ->
        MARK_RE.matchEntire(line)?.let { it.groupValues[1] + mark + it.groupValues[2] }
    }

    /** Gives the item the day [to], in place of its date (or before its text when it has none). */
    fun move(text: String, ref: ItemRef, to: LocalDate): String? = change(text, ref) { line ->
        DATED_RE.matchEntire(line)?.let { it.groupValues[1] + to + it.groupValues[2] }
            ?: UNDATED_RE.matchEntire(line)?.let { "${it.groupValues[1]}$to ${it.groupValues[2]}" }
    }

    /** The item's line now: where it was if that line is unchanged, else the one line equal to it. */
    fun locate(lines: List<String>, ref: ItemRef): Int? {
        if (ref.line in lines.indices && lines[ref.line] == ref.raw) return ref.line
        return lines.indices.filter { lines[it] == ref.raw }.singleOrNull()
    }

    private fun change(text: String, ref: ItemRef, edit: (String) -> String?): String? {
        val lines = text.split("\n").toMutableList()
        val bare = lines.map { it.removeSuffix("\r") }
        val at = locate(bare, ref) ?: return null
        val changed = edit(bare[at]) ?: return null
        lines[at] = changed + if (lines[at].endsWith("\r")) "\r" else ""
        return lines.joinToString("\n")
    }

    // ---- Answers to questions ----

    const val INBOX_HEADER = "# Inbox\n"
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val SPACES = Regex("""\s+""")

    /**
     * A line for `reminders/inbox.md`: his answer to a question, in his words, for the next Claude
     * session to put where it belongs (`reminders/README.md`, "Rules for Claude sessions on the PC").
     */
    fun inboxEntry(at: LocalDateTime, question: String, answer: String): String =
        "- ${at.format(STAMP)} (answered in the Notes app) \"$question\" ${answer.trim().split(SPACES).joinToString(" ")}"

    /** [inbox] (null when the file does not exist yet) with [entry] added at its end. */
    fun appendToInbox(inbox: String?, entry: String): String {
        val before = (inbox ?: INBOX_HEADER).trimEnd('\n')
        val gap = if (before.substringAfterLast('\n').trimStart().startsWith("- ")) "\n" else "\n\n"
        return before + gap + entry + "\n"
    }
}
