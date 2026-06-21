package com.azizjon.notes

import android.app.Application
import com.azizjon.notes.backup.BackupManager
import com.azizjon.notes.data.NotesDatabase
import com.azizjon.notes.data.NotesRepository

/** Owns the single database/repository/backup instances for the process (simple manual DI). */
class NotesApplication : Application() {
    val repository: NotesRepository by lazy {
        NotesRepository(NotesDatabase.get(this).noteDao())
    }
    val backupManager: BackupManager by lazy { BackupManager(this) }
}
