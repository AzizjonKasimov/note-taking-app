package com.azizjon.notes

import android.app.Application
import com.azizjon.notes.data.ActiveEditorSessionStore
import com.azizjon.notes.data.NotebookImageStore
import com.azizjon.notes.life.Connection
import com.azizjon.notes.life.FolderAppearanceStore
import com.azizjon.notes.life.LifeRepository
import com.azizjon.notes.life.SyncWorker
import com.azizjon.notes.reminders.ReminderAlarms
import com.azizjon.notes.reminders.ReminderNotifications
import com.azizjon.notes.reminders.Reminders
import com.azizjon.notes.voice.ModelStore
import com.azizjon.notes.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Owns the single repository/voice instances for the process (simple manual DI). */
class NotesApplication : Application() {
    /** Work that outlives a screen, such as a dictation, a sync, or the speech model's download. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val voice: VoiceController by lazy { VoiceController(this, ModelStore(this, appScope), appScope) }
    val activeEditorSessionStore: ActiveEditorSessionStore by lazy { ActiveEditorSessionStore(this) }
    val notebookImageStore: NotebookImageStore by lazy { NotebookImageStore(this) }
    val folderAppearance: FolderAppearanceStore by lazy { FolderAppearanceStore(this) }
    val life: LifeRepository by lazy { LifeRepository(this, appScope) }
    val reminders: Reminders by lazy { Reminders(this, life) }

    override fun onCreate() {
        super.onCreate()
        // JGit looks for its own settings under user.home, which Android leaves empty.
        System.setProperty("user.home", filesDir.absolutePath)
        // The app's old GitHub backup of its own notes is gone, and so is the token it kept for it.
        deleteSharedPreferences("github_backup_secrets")
        deleteSharedPreferences("github_backup")
        if (life.isCloned()) SyncWorker.schedulePeriodic(this)
        // The reminders' alarms follow the connection: set once the repo is on the phone, gone without it.
        appScope.launch {
            life.connection.collect { state ->
                when (state) {
                    Connection.Ready -> reminders.schedule()
                    Connection.Off -> {
                        ReminderAlarms.schedule(this@NotesApplication, on = false)
                        ReminderNotifications.cancelAll(this@NotesApplication)
                    }
                    is Connection.Cloning -> Unit
                }
            }
        }
    }
}
