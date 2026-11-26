package com.azizjon.notes.assistant

import com.azizjon.notes.life.FileEdit
import com.azizjon.notes.life.NoteFiles
import com.azizjon.notes.life.TextMerge
import com.azizjon.notes.reminders.ReminderPlan
import com.azizjon.notes.reminders.Reminders
import org.json.JSONArray
import org.json.JSONObject

/** What the assistant's tools need from the clone: reads that see no sync halfway, and commits. */
interface AssistantRepo {
    /** Runs [block] alone on the clone's files. It must only read. */
    suspend fun <T> read(block: (NoteFiles) -> T): T

    /** Changes files and commits just them; see `LifeRepository.commitEdit`. */
    suspend fun commitEdit(edit: (read: (String) -> String?) -> FileEdit?): Set<String>
}

/** One file of a change, before and after it (null: the file did not exist, or was deleted). */
data class FileChange(val path: String, val before: String?, val after: String?)

/** One change the assistant made, which is one commit and one line under its reply. */
data class AssistantChange(val summary: String, val files: List<FileChange>) {
    val paths: List<String> get() = files.map { it.path }
}

data class ToolResult(val output: String, val isError: Boolean = false)

/** What one turn did, collected as its tools run. */
class TurnLog {
    val changes = mutableListOf<AssistantChange>()
}

/**
 * The tools the assistant runs on the phone, against the clone: three that look (list a folder,
 * read a file, search) and three that change Markdown files (replace text, add text, create a
 * file). Each change is a commit of its own, so the app can undo it alone.
 */
class AssistantTools(private val repo: AssistantRepo) {

    fun definitions(): JSONArray = JSONArray().apply {
        put(tool("list_folder", "Lists a folder of the notes repository: its subfolders, and its files with their line counts. Use \"\" for the top.") {
            string("path", "The folder, relative to the top, such as \"ideas\" or \"dgtc/plans\"; \"\" for the top.")
            required("path")
        })
        put(tool("read_file", "Reads a text file of the repository. Long files come in parts; ask for the next part with from_line.") {
            string("path", "The file, relative to the top, such as \"reminders/upcoming.md\".")
            integer("from_line", "First line to read, from 1. Default 1.")
            integer("max_lines", "How many lines to read, at most $MAX_READ_LINES. Default $DEFAULT_READ_LINES.")
            required("path")
        })
        put(tool("search", "Finds the Markdown files whose path or lines contain the words, ignoring case. Returns at most $MAX_HITS hits as path:line: text.") {
            string("query", "The exact words to look for, such as a name or a title. At least 2 characters.")
            string("folder", "Only look in this folder and below. Leave out to search everything.")
            required("query")
        })
        put(tool("replace_text", "Replaces text in a Markdown file: old_text must appear exactly once, spaces and line breaks included. Saved at once as a change of its own.") {
            string("path", "The file to change.")
            string("old_text", "The exact text to replace, copied from read_file, with enough of its line to appear only once.")
            string("new_text", "The text to put in its place (empty to remove it).")
            string("change", CHANGE_HELP)
            required("path", "old_text", "new_text", "change")
        })
        put(tool("add_text", "Adds lines to a Markdown file: at its end, or right after one of its lines. Saved at once as a change of its own.") {
            string("path", "The file to add to.")
            string("text", "The lines to add.")
            string("after_line", "The exact text of the line to add them after, which must appear once. Leave out to add at the end.")
            string("change", CHANGE_HELP)
            required("path", "text", "change")
        })
        put(tool("create_file", "Creates a new Markdown file in an existing top-level folder (subfolders are made as needed). Saved at once as a change of its own.") {
            string("path", "The new file, ending in .md, such as \"inbox/2026-11-26-call-the-bank.md\".")
            string("text", "The file's text, starting with a # title line.")
            string("change", CHANGE_HELP)
            required("path", "text", "change")
        })
    }

    /** A few words for the progress line while [name] runs. */
    fun progressLabel(name: String, input: JSONObject): String = when (name) {
        "list_folder" -> "Looking in ${input.optString("path").ifBlank { "the top folder" }}"
        "read_file" -> "Reading ${input.optString("path")}"
        "search" -> "Searching for “${input.optString("query")}”"
        "replace_text", "add_text", "create_file" -> "Saving: ${input.optString("change").trim()}"
        else -> "Working"
    }

    suspend fun execute(name: String, input: JSONObject, log: TurnLog): ToolResult = try {
        when (name) {
            "list_folder" -> listFolder(input.optString("path"))
            "read_file" -> readFile(input.optString("path"), input.optInt("from_line", 1), input.optInt("max_lines", DEFAULT_READ_LINES))
            "search" -> search(input.optString("query"), input.optString("folder"))
            "replace_text" -> replaceText(
                input.optString("path"), input.optString("old_text"), input.optString("new_text"), input.optString("change"), log,
            )
            "add_text" -> addText(
                input.optString("path"), input.optString("text"), input.optString("after_line"), input.optString("change"), log,
            )
            "create_file" -> createFile(input.optString("path"), input.optString("text"), input.optString("change"), log)
            else -> ToolResult("There is no tool named $name.", isError = true)
        }
    } catch (e: IllegalArgumentException) {
        ToolResult(e.message ?: "That did not work.", isError = true)
    }

    // ---- Looking ----

    private suspend fun listFolder(raw: String): ToolResult {
        val folder = folderPath(raw)
        return repo.read { files ->
            val dir = if (folder.isEmpty()) files.root else files.file(folder)
            if (!dir.isDirectory) return@read ToolResult("There is no folder \"$folder\".", isError = true)
            val children = dir.listFiles().orEmpty().filterNot { it.name.startsWith(".") }.sortedBy { it.name.lowercase() }
            val folders = children.filter { it.isDirectory }.map { "- ${it.name}/" }
            val texts = children.filter { it.isFile }.map { f ->
                if (f.name.endsWith(".md", ignoreCase = true) && f.length() < MAX_FILE_BYTES) {
                    val n = lineCount(f.readText(Charsets.UTF_8))
                    "- ${f.name} ($n ${if (n == 1) "line" else "lines"})"
                } else {
                    "- ${f.name}"
                }
            }
            val shown = if (folder.isEmpty()) "the top folder" else "$folder/"
            buildString {
                append("In $shown:\n")
                if (folders.isEmpty() && texts.isEmpty()) append("(empty)\n")
                if (folders.isNotEmpty()) append("Folders:\n").append(folders.joinToString("\n")).append('\n')
                if (texts.isNotEmpty()) append("Files:\n").append(texts.joinToString("\n")).append('\n')
            }.let { ToolResult(it.trimEnd()) }
        }
    }

    private suspend fun readFile(raw: String, fromLine: Int, maxLines: Int): ToolResult {
        val path = filePath(raw)
        val text = repo.read { files ->
            val f = files.file(path)
            when {
                !f.isFile -> null
                f.length() > MAX_FILE_BYTES -> throw IllegalArgumentException("$path is too large to read here.")
                else -> f.readBytes().also { require(!TextMerge.isBinary(it)) { "$path is not a text file." } }
                    .toString(Charsets.UTF_8)
            }
        } ?: return ToolResult("There is no file \"$path\". List its folder to see what is there.", isError = true)
        val lines = text.removeSuffix("\n").split("\n")
        val from = fromLine.coerceIn(1, maxOf(1, lines.size))
        val count = maxLines.coerceIn(1, MAX_READ_LINES)
        val to = minOf(lines.size, from + count - 1)
        val body = lines.subList(from - 1, to).joinToString("\n")
        if (from == 1 && to == lines.size) return ToolResult(body)
        val more = if (to < lines.size) "\n[${lines.size - to} more lines: read on with from_line=${to + 1}]" else ""
        return ToolResult("[lines $from-$to of ${lines.size}]\n$body$more")
    }

    private suspend fun search(rawQuery: String, rawFolder: String): ToolResult {
        val query = rawQuery.trim()
        require(query.length >= 2) { "Search for at least 2 characters." }
        val folder = folderPath(rawFolder)
        val hits = repo.read { files ->
            val paths = files.listMarkdown()
                .filter { folder.isEmpty() || it.startsWith("$folder/") }
                .sorted()
            val out = ArrayList<String>()
            paths.filter { it.contains(query, ignoreCase = true) }.forEach { out += "$it (its name)" }
            for (path in paths) {
                if (out.size >= MAX_HITS) break
                val f = files.file(path)
                if (f.length() > MAX_FILE_BYTES) continue
                f.readText(Charsets.UTF_8).split("\n").forEachIndexed { i, line ->
                    if (out.size < MAX_HITS && line.contains(query, ignoreCase = true)) {
                        out += "$path:${i + 1}: ${line.trim().take(MAX_HIT_CHARS)}"
                    }
                }
            }
            out
        }
        if (hits.isEmpty()) return ToolResult("Nothing found for “$query”${if (folder.isEmpty()) "" else " in $folder/"}.")
        val more = if (hits.size >= MAX_HITS) "\n[stopped at $MAX_HITS hits: search for something narrower]" else ""
        return ToolResult(hits.joinToString("\n") + more)
    }

    // ---- Changing ----

    private suspend fun replaceText(raw: String, oldText: String, newText: String, change: String, log: TurnLog): ToolResult {
        val path = writablePath(raw)
        val summary = summary(change)
        require(oldText.isNotEmpty()) { "old_text is empty. To add lines, use add_text." }
        return write(path, summary, log) { before ->
            before ?: throw IllegalArgumentException("There is no file \"$path\". To make one, use create_file.")
            when (val n = occurrences(before, oldText)) {
                0 -> throw IllegalArgumentException(
                    "old_text is not in $path. It must match exactly, spaces and line breaks included: read the file again and copy it.",
                )
                1 -> before.replaceFirst(oldText, newText)
                else -> throw IllegalArgumentException("old_text is in $path $n times. Take more of its line, so it is there once.")
            }
        }
    }

    private suspend fun addText(raw: String, text: String, afterLine: String, change: String, log: TurnLog): ToolResult {
        val path = writablePath(raw)
        val summary = summary(change)
        val added = text.trimEnd('\n').also { require(it.isNotBlank()) { "text is empty." } }
        return write(path, summary, log) { before ->
            before ?: throw IllegalArgumentException("There is no file \"$path\". To make one, use create_file.")
            if (afterLine.isBlank()) {
                before.trimEnd('\n') + "\n" + added + "\n"
            } else {
                val lines = before.split("\n").toMutableList()
                val wanted = afterLine.trimEnd()
                val at = lines.indices.filter { lines[it].trimEnd() == wanted }
                when (at.size) {
                    0 -> throw IllegalArgumentException("after_line is not a line of $path. Copy one whole line from read_file.")
                    1 -> {
                        lines.addAll(at[0] + 1, added.split("\n"))
                        lines.joinToString("\n")
                    }
                    else -> throw IllegalArgumentException("after_line is in $path ${at.size} times. Use replace_text with more of the lines around it.")
                }
            }
        }
    }

    private suspend fun createFile(raw: String, text: String, change: String, log: TurnLog): ToolResult {
        val path = writablePath(raw)
        val summary = summary(change)
        require(text.isNotBlank()) { "text is empty." }
        val top = path.substringBefore('/')
        require('/' in path) { "New files go into a folder, such as inbox/." }
        val topExists = repo.read { files -> files.root.resolve(top).isDirectory }
        require(topExists) { "There is no folder \"$top\". New top-level folders are for the user to make: use the inbox folder instead." }
        return write(path, summary, log) { before ->
            require(before == null) { "$path already exists. Read it, and change it with replace_text or add_text." }
            text.trimEnd('\n') + "\n"
        }
    }

    /**
     * Commits [compute]'s new text for [path] as a change of its own, after the checks every write
     * gets: no clash in the file, and nothing the reminder list could no longer read.
     */
    private suspend fun write(path: String, summary: String, log: TurnLog, compute: (before: String?) -> String): ToolResult {
        var change: AssistantChange? = null
        var problem: String? = null
        repo.commitEdit { read ->
            val before = read(path)
            if (before != null && TextMerge.hasConflict(before)) {
                problem = "$path holds two versions of some lines, from the phone and from another computer. Leave it alone: " +
                    "the user settles that himself. Tell him so."
                return@commitEdit null
            }
            val after = try {
                compute(before)
            } catch (e: IllegalArgumentException) {
                problem = e.message
                return@commitEdit null
            }
            if (after == before) {
                problem = "That changes nothing in $path."
                return@commitEdit null
            }
            if (path == Reminders.UPCOMING) {
                val broken = ListCheck.newProblems(before.orEmpty(), after)
                if (broken.isNotEmpty()) {
                    problem = "Not saved: the reminder list could not read these lines as its rules say they are written:\n" +
                        broken.joinToString("\n") { "  $it" }
                    return@commitEdit null
                }
            }
            change = AssistantChange(summary, listOf(FileChange(path, before, after)))
            FileEdit("$COMMIT_PREFIX$summary", mapOf(path to after))
        }
        problem?.let { return ToolResult(it, isError = true) }
        val done = change ?: return ToolResult("That changes nothing in $path.", isError = true)
        log.changes += done
        return ToolResult("Saved in $path.")
    }

    companion object {
        const val COMMIT_PREFIX = "Assistant: "
        const val MAX_READ_LINES = 1_000
        const val DEFAULT_READ_LINES = 400
        const val MAX_HITS = 60
        private const val MAX_HIT_CHARS = 200
        private const val MAX_FILE_BYTES = 512 * 1024L
        private const val MAX_SUMMARY = 120

        private const val CHANGE_HELP = "What this change does, in a few words and the past tense, for the list under your reply " +
            "and the commit, such as \"Added a reminder to call Adam on Fri 28 Nov\"."

        /** A folder path as the tools take it: relative, with "" for the top. */
        fun folderPath(raw: String): String {
            val path = raw.trim().removePrefix("./").trim('/')
            if (path.isEmpty()) return ""
            require(NoteFiles.isSafePath(path) && path.split('/').none { it.startsWith(".") }) { "\"$raw\" is not a folder of the repository." }
            return path
        }

        fun filePath(raw: String): String {
            val path = raw.trim().removePrefix("./").removePrefix("/")
            require(NoteFiles.isSafePath(path) && path.split('/').none { it.startsWith(".") }) { "\"$raw\" is not a file of the repository." }
            return path
        }

        /** Only Markdown files outside hidden folders may change. */
        fun writablePath(raw: String): String {
            val path = filePath(raw)
            require(path.endsWith(".md", ignoreCase = true)) { "Only Markdown (.md) files can be changed here." }
            return path
        }

        private fun summary(change: String): String {
            val text = change.trim().replace(Regex("\\s+"), " ")
            require(text.isNotEmpty()) { "Say in change what this change does." }
            return text.take(MAX_SUMMARY)
        }

        fun occurrences(text: String, part: String): Int {
            var count = 0
            var at = text.indexOf(part)
            while (at >= 0) {
                count++
                at = text.indexOf(part, at + part.length)
            }
            return count
        }

        private fun lineCount(text: String): Int = if (text.isEmpty()) 0 else text.removeSuffix("\n").count { it == '\n' } + 1

        private fun tool(name: String, description: String, schema: Schema.() -> Unit): JSONObject =
            JSONObject().put("name", name).put("description", description).put("input_schema", Schema().apply(schema).json())
    }

    private class Schema {
        private val properties = JSONObject()
        private val required = JSONArray()

        fun string(name: String, description: String) {
            properties.put(name, JSONObject().put("type", "string").put("description", description))
        }

        fun integer(name: String, description: String) {
            properties.put(name, JSONObject().put("type", "integer").put("description", description))
        }

        fun required(vararg names: String) = names.forEach { required.put(it) }

        fun json(): JSONObject = JSONObject().put("type", "object").put("properties", properties).put("required", required)
            .put("additionalProperties", false)
    }
}

/** What an edit of the reminder list must not do: leave event or item lines the list cannot read. */
object ListCheck {
    /** Lines of [after] that look like events or items but that the list cannot read, and [before] did not have. */
    fun newProblems(before: String, after: String): List<String> {
        val old = unreadable(before).groupingBy { it }.eachCount().toMutableMap()
        return unreadable(after).filter { line ->
            val left = old[line] ?: 0
            if (left > 0) {
                old[line] = left - 1
                false
            } else {
                true
            }
        }
    }

    /** Lines past the first section heading that start like an event or an item but do not parse as one with a date. */
    fun unreadable(text: String): List<String> {
        val up = ReminderPlan.parseUpcoming(text)
        val read = up.events.map { it.line }.toSet() + up.items.filter { it.date != null }.map { it.line }.toSet()
        val firstSection = up.lines.indexOfFirst { it.trim().startsWith("## ") }
        if (firstSection < 0) return emptyList()
        return up.lines.withIndex()
            .filter { (i, line) -> i > firstSection && i !in read && line.trim().let { it.startsWith("- [") || it.startsWith("### ") } }
            .map { it.value.trim() }
    }
}
