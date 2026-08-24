package com.azizjon.notes.backup

import com.azizjon.notes.data.Note
import com.azizjon.notes.data.Notebook

/** A full snapshot of the user's data for backup/restore. */
data class BackupData(
    val notebooks: List<Notebook>,
    val notes: List<Note>,
)
