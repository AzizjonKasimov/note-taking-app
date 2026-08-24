package com.azizjon.notes

import android.app.Application
import com.azizjon.notes.backup.GitHubBackupSettings
import com.azizjon.notes.backup.GitHubSqlBackupManager
import com.azizjon.notes.data.NotesDatabase
import com.azizjon.notes.data.NotesRepository

/** Owns the single database/repository/backup instances for the process (simple manual DI). */
class NotesApplication : Application() {
    val repository: NotesRepository by lazy {
        NotesRepository(NotesDatabase.get(this))
    }
    val githubBackupSettings: GitHubBackupSettings by lazy { GitHubBackupSettings(this) }
    val githubSqlBackupManager: GitHubSqlBackupManager by lazy {
        GitHubSqlBackupManager(this, githubBackupSettings)
    }
}
