package com.azizjon.notes.people

import android.app.Application
import com.azizjon.notes.life.LifeRepository
import com.azizjon.notes.people.ai.AgentClient
import com.azizjon.notes.people.ai.CaptureDraftStore
import com.azizjon.notes.people.ai.GatewaySettings
import com.azizjon.notes.people.checkin.CheckinRepository
import com.azizjon.notes.people.checkin.CheckinSettings
import com.azizjon.notes.people.checkin.ContactsScanner
import com.azizjon.notes.people.data.AssistantStore
import com.azizjon.notes.people.data.NetworkDatabase
import com.azizjon.notes.people.data.NetworkRepository
import com.azizjon.notes.people.life.PeopleInLife
import com.azizjon.notes.people.life.PeopleSaveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The People part of the app, which was Network App until Notes 2.5: the people records, the
 * check-in, their copy in the notes repo (`people/`), drafts from the PC, and the people assistant.
 * One instance for the process, kept by [com.azizjon.notes.NotesApplication] like the app's other
 * singletons.
 *
 * The records live in their own database on the phone; [peopleInLife] writes a readable copy into
 * the notes repo a few seconds after each change (the user's decision of 2026-11-28), and the people
 * assistant uses the app's one AI gateway key ([gatewayToken]).
 */
class PeopleModule(
    private val context: Application,
    /** The notes repo, which holds People's copy and the PC's drafts. */
    val life: LifeRepository,
    gatewayToken: () -> String?,
) {
    /** For work that must finish even when no screen is open, such as recording a chat notification. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val repository: NetworkRepository by lazy {
        NetworkRepository(NetworkDatabase.get(context).networkDao())
    }
    val assistantStore: AssistantStore by lazy { AssistantStore(NetworkDatabase.get(context)) }
    val saveState: PeopleSaveState by lazy { PeopleSaveState(context) }
    val peopleInLife: PeopleInLife by lazy { PeopleInLife(life, repository, saveState) }
    val gatewaySettings: GatewaySettings by lazy { GatewaySettings(context, gatewayToken) }
    val agentClient: AgentClient by lazy { AgentClient(gatewayToken) }
    val captureDraftStore: CaptureDraftStore by lazy { CaptureDraftStore(context) }
    val checkinSettings: CheckinSettings by lazy { CheckinSettings(context) }
    val checkins: CheckinRepository by lazy {
        CheckinRepository(NetworkDatabase.get(context), ContactsScanner(context), appScope)
    }
}
