package com.azizjon.notes.people

import android.app.Application
import com.azizjon.notes.people.ai.AgentClient
import com.azizjon.notes.people.ai.CaptureDraftStore
import com.azizjon.notes.people.ai.GatewaySettings
import com.azizjon.notes.people.backup.GitHubBackupSettings
import com.azizjon.notes.people.backup.GitHubContents
import com.azizjon.notes.people.backup.GitHubEncryptedBackupManager
import com.azizjon.notes.people.backup.GitHubFiles
import com.azizjon.notes.people.checkin.CheckinRepository
import com.azizjon.notes.people.checkin.CheckinSettings
import com.azizjon.notes.people.checkin.ContactsScanner
import com.azizjon.notes.people.data.AssistantStore
import com.azizjon.notes.people.data.NetworkDatabase
import com.azizjon.notes.people.data.NetworkRepository
import com.azizjon.notes.people.inbox.InboxStore
import com.azizjon.notes.people.inbox.PcInbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The People part of the app, which was Network App until Notes 2.5: the people records, the
 * check-in, their encrypted backup, drafts from the PC, and the people assistant. One instance for
 * the process, kept by [com.azizjon.notes.NotesApplication] like the app's other singletons.
 *
 * The people assistant uses the app's one AI gateway key ([gatewayToken]), the one the "tell me
 * anything" box uses. Its data never goes into the life repo: it stays in its own database and its
 * own encrypted backup.
 */
class PeopleModule(private val context: Application, gatewayToken: () -> String?) {
    /** For work that must finish even when no screen is open, such as recording a chat notification. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val repository: NetworkRepository by lazy {
        NetworkRepository(NetworkDatabase.get(context).networkDao())
    }
    val assistantStore: AssistantStore by lazy { AssistantStore(NetworkDatabase.get(context)) }
    val backupSettings: GitHubBackupSettings by lazy { GitHubBackupSettings(context) }
    private val gitHubFiles: GitHubFiles by lazy { GitHubContents() }
    val backupManager: GitHubEncryptedBackupManager by lazy {
        GitHubEncryptedBackupManager(repository, backupSettings, gitHubFiles)
    }
    val pcInbox: PcInbox by lazy { PcInbox(gitHubFiles, InboxStore(context)) }
    val gatewaySettings: GatewaySettings by lazy { GatewaySettings(context, gatewayToken) }
    val agentClient: AgentClient by lazy { AgentClient(gatewayToken) }
    val captureDraftStore: CaptureDraftStore by lazy { CaptureDraftStore(context) }
    val checkinSettings: CheckinSettings by lazy { CheckinSettings(context) }
    val checkins: CheckinRepository by lazy {
        CheckinRepository(NetworkDatabase.get(context), ContactsScanner(context), appScope)
    }
}
