package com.azizjon.notes

import android.app.Application
import com.azizjon.notes.assistant.Assistant
import com.azizjon.notes.assistant.AssistantChange
import com.azizjon.notes.assistant.AssistantChoiceStore
import com.azizjon.notes.assistant.AssistantRepo
import com.azizjon.notes.assistant.AssistantSession
import com.azizjon.notes.assistant.AssistantTools
import com.azizjon.notes.assistant.FileChange
import com.azizjon.notes.assistant.GatewayClient
import com.azizjon.notes.data.ActiveEditorSessionStore
import com.azizjon.notes.data.NotebookImageStore
import com.azizjon.notes.life.Connection
import com.azizjon.notes.life.FileEdit
import com.azizjon.notes.life.FolderAppearanceStore
import com.azizjon.notes.life.LifeRepository
import com.azizjon.notes.life.NoteFiles
import com.azizjon.notes.life.SyncWorker
import com.azizjon.notes.life.TokenStore
import com.azizjon.notes.people.PeopleModule
import com.azizjon.notes.people.checkin.EveningCheckin
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

    /** The AI gateway's key, which the user enters in Settings. */
    val gatewayKey: TokenStore by lazy { TokenStore(this, TokenStore.GATEWAY_PREFS_NAME, TokenStore.GATEWAY_KEY_ALIAS) }

    /** People (Network App until 2.5): its records, check-in, backup, PC drafts, and assistant. */
    val people: PeopleModule by lazy { PeopleModule(this) { gatewayKey.read() } }

    /** The "tell me anything" box on Today: the assistant's conversation since the app started. */
    val assistant: AssistantSession by lazy {
        val repo = object : AssistantRepo {
            override suspend fun <T> read(block: (NoteFiles) -> T): T = life.readTree(block)
            override suspend fun commitEdit(edit: (read: (String) -> String?) -> FileEdit?): Set<String> = life.commitEdit(edit)
        }
        val runner = Assistant(
            gateway = GatewayClient(token = { gatewayKey.read() }),
            tools = AssistantTools(repo),
            repo = repo,
            afterTurn = { changes ->
                // A new event gets its checklist's steps at once, as the bot added them after each reply.
                if (changes.none { Reminders.UPCOMING in it.paths }) {
                    emptyList()
                } else {
                    listOfNotNull(
                        reminders.expandNow()?.let { added ->
                            AssistantChange(
                                summary = added.message.removePrefix("Reminders: ").replaceFirstChar { it.uppercase() },
                                files = listOf(FileChange(Reminders.UPCOMING, added.before, added.after)),
                            )
                        },
                    )
                }
            },
        )
        AssistantSession(appScope, runner, repo, AssistantChoiceStore(this))
    }

    override fun onCreate() {
        super.onCreate()
        // JGit looks for its own settings under user.home, which Android leaves empty.
        System.setProperty("user.home", filesDir.absolutePath)
        // The app's old GitHub backup of its own notes is gone, and so is the token it kept for it.
        deleteSharedPreferences("github_backup_secrets")
        deleteSharedPreferences("github_backup")
        if (life.isCloned()) SyncWorker.schedulePeriodic(this)
        // Re-creates People's evening check-in if its schedule was ever lost; leaves a live one alone.
        EveningCheckin.schedule(this, people.checkinSettings.state, replace = false)
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
