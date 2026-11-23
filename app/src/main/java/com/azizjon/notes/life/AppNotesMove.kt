package com.azizjon.notes.life

import com.azizjon.notes.data.DEFAULT_NOTEBOOK_NAME
import com.azizjon.notes.data.Note
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Moves the notes the app kept itself (its old Room list) into the repo's inbox folder, once.
 *
 * Its notes as of the backup of 2026-11-23 19:28 (note ids up to 31) were copied into the repo's
 * topics by hand that day. Anything written or changed in the app after that is moved here, so
 * nothing written in the old list is left behind when the app stops showing it. The old database
 * stays on the phone, untouched.
 */
object AppNotesMove {
    const val INBOX = "inbox"
    private const val MOVED_UP_TO_ID = 31L
    private const val MOVED_BACKUP_AT = 1_795_429_692_763L
    private val WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** Notes with text, not in the old Trash, that were written or changed after the hand move. */
    fun toMove(notes: List<Note>): List<Note> = notes.filter { note ->
        val hasText = note.title.isNotBlank() || note.content.isNotBlank()
        val newSinceMove = note.id > MOVED_UP_TO_ID || note.updatedAt > MOVED_BACKUP_AT
        hasText && newSinceMove && note.deletedAt == null
    }

    /** Writes each note as a Markdown file in the inbox. Returns the paths written. */
    fun write(files: NoteFiles, notes: List<Note>, notebookNames: Map<Long, String>): List<String> =
        notes.sortedBy { it.createdAt }.map { note ->
            val title = note.title.trim()
            val created = LocalDateTime.ofInstant(Instant.ofEpochMilli(note.createdAt), ZoneId.systemDefault())
            val path = title.takeIf { it.isNotEmpty() }?.let { files.pathForTitle("$INBOX/note.md", it) }
                ?: files.newNotePath(INBOX, created)
            val changed = LocalDateTime.ofInstant(Instant.ofEpochMilli(note.updatedAt), ZoneId.systemDefault())
            val notebook = notebookNames[note.notebookId] ?: DEFAULT_NOTEBOOK_NAME
            val origin = "<!-- From the Notes app's \"$notebook\" notebook, last changed ${changed.format(WHEN)}. -->"
            val body = note.content.trimEnd().let { if (it.isEmpty()) origin else "$it\n\n$origin" } + "\n"
            files.write(path, if (title.isEmpty()) body else joinTitle(title, NEW_NOTE_PREFIX, body))
            path
        }
}
