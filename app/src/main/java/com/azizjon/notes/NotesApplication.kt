package com.azizjon.notes

import android.app.Application
import com.azizjon.notes.backup.GitHubBackupSettings
import com.azizjon.notes.backup.GitHubSqlBackupManager
import com.azizjon.notes.data.NotesDatabase
import com.azizjon.notes.data.ActiveEditorSessionStore
import com.azizjon.notes.data.NotebookImageStore
import com.azizjon.notes.data.NotesRepository
import com.azizjon.notes.voice.ModelStore
import com.azizjon.notes.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Owns the single database/repository/backup instances for the process (simple manual DI). */
class NotesApplication : Application() {
    /** Work that outlives a screen, such as a dictation or the speech model's download. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val voice: VoiceController by lazy { VoiceController(this, ModelStore(this, appScope), appScope) }
    val activeEditorSessionStore: ActiveEditorSessionStore by lazy { ActiveEditorSessionStore(this) }
    val notebookImageStore: NotebookImageStore by lazy { NotebookImageStore(this) }
    val repository: NotesRepository by lazy {
        NotesRepository(NotesDatabase.get(this))
    }
    val githubBackupSettings: GitHubBackupSettings by lazy { GitHubBackupSettings(this) }
    val githubSqlBackupManager: GitHubSqlBackupManager by lazy {
        GitHubSqlBackupManager(this, githubBackupSettings, notebookImageStore)
    }
}
