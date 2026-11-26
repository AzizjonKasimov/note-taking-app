package com.azizjon.notes.assistant

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The assistant's instructions. The app holds only the general ones; what is personal (where each
 * kind of thing goes, whose names to leave out) comes from the repository itself, read at the start
 * of every turn, so the repository's owner and the agents on their PC can change it without an app
 * release.
 */
object AssistantPrompt {
    /** The repository's own rules for the assistant, at its top. */
    const val RULES_FILE = "ASSISTANT.md"

    /** The reminder list's rules, whose sections on writing the list are sent along. */
    const val LIST_RULES_FILE = "reminders/README.md"
    private val LIST_SECTIONS = listOf("How `upcoming.md` is written", "Rules for the bot")

    private val CORE = """
        You are the assistant inside a notes app. The user keeps their notes as Markdown files in a
        git repository that the app holds on their phone. They tell you things in their own words,
        typed or spoken; speech recognition can mishear a word, above all a name, so use the spelling
        their files already use.

        Your job is to put what they say where it belongs, so they do not have to: a dated to-do or a
        reminder into the reminder list, an idea with their ideas, a fact into its topic's file, and
        whatever you cannot place into the inbox folder, for later. When they ask a question, answer
        it from the files and change nothing.

        How to work:
        - Look with list_folder, read_file, and search; change with replace_text, add_text, and
          create_file. Each change is saved at once as a change of its own, which the user can undo
          in the app. The app lists your changes under your reply.
        - Be quick: every step costs the user seconds of waiting. Read only what you need, and do not
          explain your reasoning.
        - Read a file before you change it, and change only what their words call for. Keep their
          words; shorten them only to fit how the file is written.
        - The repository's own rules below say where things go and how each file is written. They
          come before these general ones.
        - Never invent a fact. When something that matters is missing, save what they said, and ask
          one short question in your reply.
        - Write dates as YYYY-MM-DD and work out weekdays from today's date, which the input gives.
          "Friday" means the next Friday from today.
        - Never change lines between <<<<<<< and >>>>>>> markers: they hold a clash between the phone
          and another computer, which the user settles.
        - Never make a new top-level folder. Something that needs one goes into the inbox folder, and
          your reply says so.

        Your reply: one to three short sentences of plain text, no Markdown. Say what you saved and
        where, in the user's terms ("Added a reminder for Friday to call Adam."), or answer the
        question.
    """.trimIndent()

    /** The system prompt: the general rules, then the repository's. Missing files are left out. */
    fun system(rules: String?, listRules: String?): String = buildString {
        append(CORE)
        rules?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("\n\n# The repository's rules for you ($RULES_FILE)\n\n").append(it)
        }
        listRules?.let(::listSections)?.takeIf { it.isNotEmpty() }?.let {
            append("\n\n# The reminder list's rules (from $LIST_RULES_FILE)\n\n").append(it)
        }
    }

    /** The sections of the list's README that say how to write and change it. */
    fun listSections(readme: String): String {
        val out = ArrayList<String>()
        var keep = false
        for (line in readme.split("\n")) {
            if (line.startsWith("## ")) keep = line.removePrefix("## ").trim() in LIST_SECTIONS
            if (keep) out += line
        }
        return out.joinToString("\n").trim()
    }

    /** One earlier message of this conversation, for follow-ups such as "no, make it Saturday". */
    data class Earlier(val message: String, val reply: String?, val changes: List<String>)

    fun input(message: String, earlier: List<Earlier>, now: ZonedDateTime): String = buildString {
        append("Today is ").append(now.format(NOW)).append(" (").append(now.zone.id).append(").\n")
        if (earlier.isNotEmpty()) {
            append("\nEarlier in this conversation, oldest first:\n")
            for (e in earlier) {
                append("- The user said: ").append(quote(e.message)).append('\n')
                e.reply?.let { append("  You replied: ").append(quote(it)).append('\n') }
                if (e.changes.isNotEmpty()) append("  Your changes: ").append(e.changes.joinToString("; ")).append('\n')
            }
        }
        append("\nThe user's message:\n").append(message.trim())
    }

    private fun quote(text: String) = "\"" + text.trim().replace("\n", " ") + "\""

    private val NOW = DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd, HH:mm", Locale.ENGLISH)
}
