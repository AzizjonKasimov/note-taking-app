package com.azizjon.notes.people.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.azizjon.notes.NotesApplication
import com.azizjon.notes.people.ai.ActionState
import com.azizjon.notes.people.ai.AgentClient
import com.azizjon.notes.people.ai.AiRequestService
import com.azizjon.notes.people.ai.AssistantAgent
import com.azizjon.notes.people.ai.AssistantPrompt
import com.azizjon.notes.people.ai.AssistantTools
import com.azizjon.notes.people.ai.AttachedPhoto
import com.azizjon.notes.people.ai.ChatAttachment
import com.azizjon.notes.people.ai.ChatMessage
import com.azizjon.notes.people.ai.ChatPhase
import com.azizjon.notes.people.ai.ChatRole
import com.azizjon.notes.people.ai.ChatState
import com.azizjon.notes.people.ai.GatewaySettingsState
import com.azizjon.notes.people.ai.PendingAction
import com.azizjon.notes.people.ai.PendingItem
import com.azizjon.notes.people.ai.PhotoException
import com.azizjon.notes.people.ai.PhotoLimits
import com.azizjon.notes.people.ai.PhotoPreparer
import com.azizjon.notes.people.checkin.ChatSources
import com.azizjon.notes.people.checkin.CheckinEntity
import com.azizjon.notes.people.checkin.CheckinList
import com.azizjon.notes.people.checkin.CheckinRules
import com.azizjon.notes.people.checkin.CheckinSettingsState
import com.azizjon.notes.people.checkin.EveningCheckin
import com.azizjon.notes.people.checkin.NewPersonItem
import com.azizjon.notes.people.data.AffiliationEntity
import com.azizjon.notes.people.data.AiFeedbackEntity
import com.azizjon.notes.people.data.CapabilityEntity
import com.azizjon.notes.people.data.FactEntity
import com.azizjon.notes.people.data.InteractionEntity
import com.azizjon.notes.people.data.MoveDestination
import com.azizjon.notes.people.data.NeedEntity
import com.azizjon.notes.people.data.NetworkSnapshot
import com.azizjon.notes.people.data.PersonDraft
import com.azizjon.notes.people.data.PersonEntity
import com.azizjon.notes.people.data.UndoConflictException
import com.azizjon.notes.people.feedback.AiFeedbackLabel
import com.azizjon.notes.people.feedback.buildFeedback
import com.azizjon.notes.people.feedback.installedAppVersion
import com.azizjon.notes.people.life.InboxDraft
import com.azizjon.notes.people.life.PeopleRemovalException
import com.azizjon.notes.people.life.SaveStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SaveUiState(
    val status: SaveStatus,
    /** Whether this build saves People to the notes repo: always, but a test build only once switched on. */
    val owns: Boolean,
    /** How many people's files the notes repo holds, once looked at. */
    val folderPeople: Int? = null,
    val busy: Boolean = false,
    /** How many people's files the last save would have removed, when it was held back for that; 0 otherwise. */
    val removalsWaiting: Int = 0,
)

data class FeedbackUiState(val reports: List<AiFeedbackEntity> = emptyList())

class NetworkViewModel(application: Application) : AndroidViewModel(application) {
    private val app = (application as NotesApplication).people
    private val context: Application = application
    private val repository = app.repository
    private val saveStore = app.saveState
    private val peopleInLife = app.peopleInLife
    private val gatewaySettings = app.gatewaySettings
    private val assistantStore = app.assistantStore
    private val captureDraftStore = app.captureDraftStore
    private val agent = AssistantAgent(
        client = app.agentClient,
        tools = AssistantTools(
            store = assistantStore,
            // Marked before the write rather than after the turn, so a process
            // killed mid-turn still leaves the save to the notes repo due.
            beforeWrite = { saveStore.markSaveNeeded() },
        ),
    )

    val snapshot: StateFlow<NetworkSnapshot> = repository.observeSnapshot().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        NetworkSnapshot(),
    )

    private val _saveState = MutableStateFlow(SaveUiState(saveStore.status, peopleInLife.owns))
    val saveState: StateFlow<SaveUiState> = _saveState.asStateFlow()

    /** How many people came over from Network App on the phone, or null until "Move to Notes" there. */
    private val _movedFromNetworkApp = MutableStateFlow(saveStore.movedFromNetworkApp)
    val movedFromNetworkApp: StateFlow<Int?> = _movedFromNetworkApp.asStateFlow()

    /**
     * The move from Network App happens in a screen of its own, so the activity asks again on
     * coming back, and a save the move left due starts then.
     */
    fun refreshAfterMove() {
        _movedFromNetworkApp.value = saveStore.movedFromNetworkApp
        refreshSaveState()
        if (saveStore.status.saveNeeded) scheduleSave()
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _composerDraft = MutableStateFlow("")
    val composerDraft: StateFlow<String> = _composerDraft.asStateFlow()

    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat.asStateFlow()

    /** True while the one-time assistant disclosure is waiting on an answer. */
    private val _consentRequested = MutableStateFlow(false)
    val consentRequested: StateFlow<Boolean> = _consentRequested.asStateFlow()

    val feedbackState: StateFlow<FeedbackUiState> = repository.observeFeedback()
        .map(::FeedbackUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedbackUiState())

    /** The assistant message the report sheet is open for, if any. */
    private val _feedbackTarget = MutableStateFlow<ChatMessage?>(null)
    val feedbackTarget: StateFlow<ChatMessage?> = _feedbackTarget.asStateFlow()

    private val _gatewaySettingsState = MutableStateFlow(gatewaySettings.state)
    val gatewaySettingsState: StateFlow<GatewaySettingsState> = _gatewaySettingsState.asStateFlow()

    /** Photos attached to the unsent message. Memory only, like the thread. */
    private val _photos = MutableStateFlow<List<AttachedPhoto>>(emptyList())
    val photos: StateFlow<List<AttachedPhoto>> = _photos.asStateFlow()

    /** Photos still being shrunk; sending waits for them. */
    private val _preparingPhotos = MutableStateFlow(0)
    val preparingPhotos: StateFlow<Int> = _preparingPhotos.asStateFlow()

    /** A tab another part of the app asked to show, such as a shared photo opening the Assistant. */
    private val _requestedSection = MutableStateFlow<AppSection?>(null)
    val requestedSection: StateFlow<AppSection?> = _requestedSection.asStateFlow()

    /** Drafts a session on the PC left in the notes repo (`people/drafts/`), oldest first. */
    private val _inboxDrafts = MutableStateFlow<List<InboxDraft>>(emptyList())
    val inboxDrafts: StateFlow<List<InboxDraft>> = _inboxDrafts.asStateFlow()

    private val photoPreparer = PhotoPreparer(context)
    private var nextPhotoId = 1L

    /** Set when something filled the composer for the user, so it opens ready to type. */
    private val _composerFocusRequested = MutableStateFlow(false)
    val composerFocusRequested: StateFlow<Boolean> = _composerFocusRequested.asStateFlow()

    private val checkins = app.checkins
    private val checkinSettingsStore = app.checkinSettings

    private val _checkinSettings = MutableStateFlow(checkinSettingsStore.state)
    val checkinSettings: StateFlow<CheckinSettingsState> = _checkinSettings.asStateFlow()

    /** Re-evaluated hourly as well, so a "later" comes back without waiting for a change. */
    val checkinList: StateFlow<CheckinList> = combine(
        checkins.observe(),
        snapshot,
        flow {
            while (true) {
                emit(Unit)
                delay(CHECKIN_REFRESH_MS)
            }
        },
    ) { rows, network, _ -> CheckinRules.build(rows, network, System.currentTimeMillis()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CheckinList())

    private var saveJob: Job? = null
    private var chatJob: Job? = null
    private var draftSaveJob: Job? = null
    private var nextMessageId = 1L

    init {
        if (saveStore.status.saveNeeded) scheduleSave()
        refreshCheckinSources()
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                runCatching { captureDraftStore.read() }.getOrDefault("")
            }
            // Never clobber something typed while the encrypted read was running.
            if (restored.isNotBlank() && _composerDraft.value.isBlank()) _composerDraft.value = restored
        }
        // Drafts from the PC and people's files arrive with a sync of the notes repo, or with its
        // first download on a new phone, where an empty People offers to load them.
        viewModelScope.launch {
            app.life.sync.map { it.lastSyncAt }.distinctUntilChanged().collect {
                checkInbox()
                refreshFolderPeople()
            }
        }
    }

    fun showMessage(value: String) {
        _message.value = value
    }

    fun clearMessage() {
        _message.value = null
    }

    fun savePerson(draft: PersonDraft, onSaved: (Long) -> Unit = {}) = mutate {
        val id = repository.savePerson(draft)
        onSaved(id)
    }

    fun deletePerson(person: PersonEntity, onDeleted: () -> Unit = {}) = mutate {
        repository.deletePerson(person)
        onDeleted()
    }

    fun addInteraction(personId: Long, note: String) = mutate {
        repository.addInteraction(personId, note)
    }

    fun addNeed(personId: Long, text: String) = mutate {
        repository.addNeed(personId, text)
    }

    fun addCapability(personId: Long, text: String) = mutate {
        repository.addCapability(personId, text)
    }

    fun addAffiliation(personId: Long, organization: String, role: String, education: Boolean) = mutate {
        repository.addAffiliation(personId, organization, role, education)
    }

    fun addFact(personId: Long, text: String) = mutate { repository.addFact(personId, text) }

    /** Moves a note filed against the wrong person onto the right one, from the person screen. */
    fun moveInteraction(
        interactionId: Long,
        destination: MoveDestination,
        onMoved: (Long) -> Unit = {},
    ) = mutate {
        val result = repository.moveInteraction(interactionId, destination)
        showMessage("Moved ${result.summary} to ${result.personName}")
        onMoved(result.personId)
    }

    fun deleteFact(item: FactEntity) = mutate { repository.deleteFact(item) }

    fun setAffiliationCurrent(item: AffiliationEntity, current: Boolean) = mutate {
        repository.setAffiliationCurrent(item, current)
    }

    fun deleteAffiliation(item: AffiliationEntity) = mutate { repository.deleteAffiliation(item) }

    fun deleteInteraction(item: InteractionEntity) = mutate { repository.deleteInteraction(item) }
    fun deleteNeed(item: NeedEntity) = mutate { repository.deleteNeed(item) }
    fun deleteCapability(item: CapabilityEntity) = mutate { repository.deleteCapability(item) }

    // Each tap takes the card off the list being looked at, so say where it went.
    fun setNeedHelped(item: NeedEntity, helped: Boolean) = mutate {
        repository.setNeedHelped(item, helped)
        showMessage(if (helped) "Moved to Helped" else "Moved back to To help")
    }

    fun setNeedActive(item: NeedEntity, active: Boolean) = mutate {
        repository.setNeedActive(item, active)
        showMessage(if (active) "Reopened" else "Moved to Closed")
    }

    fun updateComposerDraft(value: String) {
        if (_composerDraft.value == value) return
        _composerDraft.value = value
        persistDraft(value)
    }

    /**
     * Shrinks and attaches photos to the unsent message, up to the limit.
     *
     * [fromCamera] marks a capture this app owns, which is deleted once read so
     * a photo of someone's card does not linger in the cache.
     */
    fun attachPhotos(uris: List<Uri>, fromCamera: Boolean = false) {
        if (uris.isEmpty()) return
        val room = PhotoLimits.MAX_PHOTOS - _photos.value.size - _preparingPhotos.value
        val accepted = uris.take(room.coerceAtLeast(0))
        if (accepted.size < uris.size) {
            showMessage("A message can carry ${PhotoLimits.MAX_PHOTOS} photos, so not all of them were added.")
        }
        if (fromCamera) uris.drop(accepted.size).forEach(photoPreparer::deleteCapture)
        accepted.forEach { uri ->
            _preparingPhotos.value += 1
            viewModelScope.launch {
                try {
                    val photo = photoPreparer.prepare(uri, nextPhotoId++)
                    _photos.value = (_photos.value + photo).take(PhotoLimits.MAX_PHOTOS)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: PhotoException) {
                    showMessage(error.message ?: UNREADABLE_PHOTO)
                } catch (error: Exception) {
                    // Decoder messages are technical; the user only needs to know it failed.
                    showMessage(UNREADABLE_PHOTO)
                } finally {
                    _preparingPhotos.value -= 1
                    if (fromCamera) photoPreparer.deleteCapture(uri)
                }
            }
        }
    }

    fun removePhoto(id: Long) {
        _photos.value = _photos.value.filterNot { it.id == id }
    }

    /** Where the camera app should write a photo for the composer. */
    fun newCaptureUri(): Uri = photoPreparer.newCaptureUri()

    /**
     * Takes what another app shared: photos join the composer, text is added
     * to it, and the Assistant tab opens. Nothing is sent until the user does.
     */
    fun receiveShare(text: String?, photos: List<Uri>) {
        text?.trim()?.takeIf(String::isNotEmpty)?.let { shared ->
            val current = _composerDraft.value.trimEnd()
            val combined = if (current.isEmpty()) shared else "$current\n$shared"
            updateComposerDraft(combined.take(AgentClient.MAX_INPUT_CHARACTERS))
        }
        attachPhotos(photos)
        openSection(AppSection.CHAT)
    }

    /**
     * Takes drafts the PC queued. Runs whenever the app comes to the front, at
     * most every [INBOX_CHECK_INTERVAL_MS]; a failure is kept for Settings to
     * show rather than interrupting whatever the app was opened for.
     */
    /** Reads the drafts waiting in the notes repo: a look at the phone's copy, so any screen may ask. */
    fun checkInbox() {
        viewModelScope.launch {
            _inboxDrafts.value = runCatching { peopleInLife.drafts() }.getOrDefault(emptyList())
        }
    }

    /**
     * Sends a draft from the PC as the next message, exactly as if the user had
     * typed it. It goes through the message box, so the permission prompt or a
     * missing token leaves it there rather than losing it.
     */
    fun sendInboxDraft(id: String) {
        val draft = inboxDrafts.value.firstOrNull { it.id == id } ?: return
        if (_chat.value.phase.busy) return
        if (_composerDraft.value.isNotBlank() || _photos.value.isNotEmpty() || _preparingPhotos.value > 0) {
            showMessage("Send or clear your own message first.")
            return
        }
        removeInboxDraft(id)
        updateComposerDraft(draft.text)
        sendChatMessage()
    }

    /** Moves a draft from the PC into the message box, after anything already typed. */
    fun editInboxDraft(id: String) {
        val draft = inboxDrafts.value.firstOrNull { it.id == id } ?: return
        val typed = _composerDraft.value.trimEnd()
        val combined = if (typed.isEmpty()) draft.text else "$typed\n${draft.text}"
        if (combined.length > AgentClient.MAX_INPUT_CHARACTERS) {
            showMessage("That draft does not fit after what you typed. Send or clear your message first.")
            return
        }
        removeInboxDraft(id)
        updateComposerDraft(combined)
        _composerFocusRequested.value = true
    }

    fun discardInboxDraft(id: String) {
        if (inboxDrafts.value.any { it.id == id }) removeInboxDraft(id)
    }

    /** Hides the draft at once, so a second tap cannot use it twice, then removes its file from the notes repo. */
    private fun removeInboxDraft(id: String) {
        _inboxDrafts.value = _inboxDrafts.value.filterNot { it.id == id }
        viewModelScope.launch { runCatching { peopleInLife.takeDraft(id) } }
    }

    fun openSection(section: AppSection) {
        _requestedSection.value = section
    }

    fun clearRequestedSection() {
        _requestedSection.value = null
    }

    fun composerFocused() {
        _composerFocusRequested.value = false
    }

    /**
     * Starts a message about someone from the check-in. The user finishes and
     * sends it; the assistant then files it like anything else they tell it.
     */
    private fun prefillAssistant(text: String) {
        val current = _composerDraft.value.trimEnd()
        updateComposerDraft((if (current.isEmpty()) text else "$current\n$text").take(AgentClient.MAX_INPUT_CHARACTERS))
        _composerFocusRequested.value = true
        openSection(AppSection.CHAT)
    }

    /**
     * The name is quoted because it is exactly how the phone or the chat app
     * shows the person, which the assistant keeps as another name when they
     * turn out to be saved under a different one.
     */
    fun checkinAddPerson(item: NewPersonItem) {
        val where = when (val source = item.sources.first()) {
            CheckinEntity.SOURCE_CONTACTS -> "saved in my phone"
            else -> "wrote to me on ${ChatSources.label(source)}"
        }
        prefillAssistant("\"${item.name}\" ($where): ")
        setCheckinStatus(item.refs, CheckinEntity.STATUS_DONE)
    }

    /**
     * The check-in's "Already saved". Only the name is saved: its rows keep
     * their answers, so a recent chat with the person can still be asked about
     * under Talked to recently.
     */
    fun checkinLinkPerson(item: NewPersonItem, personId: Long) = mutate {
        val person = repository.addOtherName(personId, item.name)
        showMessage("Saved \"${item.name}\" as another name for ${if (person.isSelf) "you" else person.name}")
    }

    fun checkinAddNote(name: String, refs: List<String>) {
        prefillAssistant("About $name: ")
        if (refs.isNotEmpty()) setCheckinStatus(refs, CheckinEntity.STATUS_DONE)
    }

    fun setCheckinStatus(refs: List<String>, status: String) {
        viewModelScope.launch {
            try {
                checkins.setStatus(refs, status)
            } catch (error: Exception) {
                showMessage(error.message ?: "Could not save that answer")
            }
        }
    }

    fun skipAllNewPeople() {
        val people = checkinList.value.newPeople
        setCheckinStatus(people.flatMap { it.refs }, CheckinEntity.STATUS_NEVER)
        showMessage("Skipped ${people.size} people")
    }

    fun setRecordCheckinStatus(ref: String, status: String) {
        viewModelScope.launch {
            try {
                checkins.setRecordStatus(ref, status)
            } catch (error: Exception) {
                showMessage(error.message ?: "Could not save that answer")
            }
        }
    }

    // Record answers change the network, so they go through mutate() and the save marker.
    fun confirmNeedStillOpen(item: NeedEntity) = mutate { repository.confirmNeed(item) }

    fun closeNeedFromCheckin(item: NeedEntity) = mutate {
        repository.setNeedActive(item, false)
        showMessage("Moved to Closed on the Needs tab")
    }

    fun confirmPosition(item: AffiliationEntity) = mutate { repository.confirmAffiliation(item) }

    fun endPosition(item: AffiliationEntity) = mutate {
        repository.endAffiliation(item)
        showMessage("Marked as a past position. Tell the assistant where they are now.")
    }

    fun setEveningReminder(enabled: Boolean, minutes: Int) {
        checkinSettingsStore.setEvening(enabled, minutes)
        refreshCheckinSettings()
        EveningCheckin.schedule(context, checkinSettingsStore.state, replace = true)
    }

    fun setContactsSource(enabled: Boolean) {
        checkinSettingsStore.setContacts(enabled)
        refreshCheckinSettings()
        if (enabled) refreshCheckinSources()
    }

    fun setChatsSource(enabled: Boolean) {
        checkinSettingsStore.setChats(enabled)
        refreshCheckinSettings()
    }

    /** Picks up contacts saved since the last look. Cheap: names only. */
    fun refreshCheckinSources() {
        if (!checkinSettingsStore.state.contactsEnabled) return
        viewModelScope.launch {
            try {
                checkins.refreshContacts()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The address book was unreadable this time; the next look will try again.
            }
        }
    }

    private fun refreshCheckinSettings() {
        _checkinSettings.value = checkinSettingsStore.state
    }

    /**
     * Sends the composer text to the assistant as the next turn in the thread.
     *
     * The first message waits on the one-time disclosure: from here on the
     * assistant reads whichever records it decides it needs, and the user should
     * agree to that once before it happens.
     */
    fun sendChatMessage() {
        val text = _composerDraft.value.trim()
        val photos = _photos.value
        if (_chat.value.phase.busy || (text.isEmpty() && photos.isEmpty())) return
        if (_preparingPhotos.value > 0) {
            showMessage("Still getting the photos ready. Try again in a moment.")
            return
        }
        if (text.length > AgentClient.MAX_INPUT_CHARACTERS) {
            showMessage("That message is too long. Keep it under ${AgentClient.MAX_INPUT_CHARACTERS} characters.")
            return
        }
        if (!agent.configured) {
            showMessage("Add the AI key in Settings → Assistant first.")
            return
        }
        if (!_gatewaySettingsState.value.assistantConsent) {
            _consentRequested.value = true
            return
        }
        val history = AssistantPrompt.history(_chat.value.messages)
        appendUser(text, photos)
        _composerDraft.value = ""
        _photos.value = emptyList()
        viewModelScope.launch { clearDraft() }

        chatJob?.cancel()
        chatJob = viewModelScope.launch {
            setPhase(ChatPhase.Working(if (photos.isEmpty()) "Reading your message…" else "Reading your photos…"))
            try {
                val outcome = AiRequestService.holdingProcess(context) {
                    agent.run(text, photos.map { it.jpeg }, history) { status -> setPhase(ChatPhase.Working(status)) }
                }
                val log = outcome.log
                if (log.changes.isNotEmpty()) {
                    refreshSaveState()
                    scheduleSave()
                }
                val attachment = if (log.saved.isEmpty() && log.pending.isEmpty()) {
                    null
                } else {
                    ChatAttachment.AgentResult(
                        saved = log.saved.toList(),
                        changes = log.changes.toList(),
                        memory = log.memory.toList(),
                        pending = log.pending.map { PendingItem(it) },
                        people = log.people.toList(),
                        calls = log.calls.toList(),
                    )
                }
                if (outcome.error == null) {
                    appendAssistant(outcome.reply.orEmpty(), attachment, fromGateway = true)
                } else {
                    appendAssistant(outcome.error, failed = true, fromGateway = true)
                    // Whatever landed before the failure is still saved, so it
                    // still gets its card and its undo.
                    if (attachment != null) appendAssistant("This was saved before it stopped.", attachment)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appendAssistant(error.message ?: "The assistant could not complete the request.", failed = true, fromGateway = true)
            } finally {
                setPhase(ChatPhase.Idle)
            }
        }
    }

    fun confirmAssistantConsent() {
        gatewaySettings.setAssistantConsent(true)
        refreshGatewaySettingsState()
        _consentRequested.value = false
        sendChatMessage()
    }

    fun dismissAssistantConsent() {
        _consentRequested.value = false
        showMessage("The assistant needs that permission. Browsing and local search on the People tab still work.")
    }

    /**
     * Puts back everything one reply saved, all or nothing.
     *
     * Refuses when something was edited since, rather than overwriting the newer
     * version; the card then says so and stops offering undo.
     */
    fun undoChatChanges(messageId: Long) {
        val result = agentResult(messageId)?.takeIf { it.canUndo } ?: return
        if (_chat.value.phase.busy) return
        chatJob?.cancel()
        chatJob = viewModelScope.launch {
            setPhase(ChatPhase.Undoing)
            saveStore.markSaveNeeded()
            refreshSaveState()
            try {
                assistantStore.undo(result.changes, System.currentTimeMillis())
                updateAgentResult(messageId) { it.copy(undone = true) }
                appendAssistant("Undone. Everything that reply saved is back the way it was.")
                scheduleSave()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (conflict: UndoConflictException) {
                updateAgentResult(messageId) { it.copy(undoError = conflict.message) }
            } catch (error: Exception) {
                updateAgentResult(messageId) { it.copy(undoError = error.message ?: "Undo failed. Nothing was changed.") }
            } finally {
                setPhase(ChatPhase.Idle)
            }
        }
    }

    /** Carries out a delete or merge the user just confirmed on the card. */
    fun confirmChatAction(messageId: Long, actionId: String) {
        val item = agentResult(messageId)?.pending?.firstOrNull { it.action.id == actionId && it.state == ActionState.PENDING } ?: return
        if (_chat.value.phase.busy) return
        chatJob?.cancel()
        chatJob = viewModelScope.launch {
            setPhase(ChatPhase.Confirming)
            saveStore.markSaveNeeded()
            refreshSaveState()
            try {
                val outcome = when (val action = item.action) {
                    is PendingAction.DeletePerson -> "Deleted ${assistantStore.deletePerson(action.personId)}."
                    is PendingAction.DeleteNote -> assistantStore.deleteNote(action.noteId).let { "Deleted the note." }
                    is PendingAction.DeleteRecord -> assistantStore.deleteRecord(action.ref).let { "Deleted." }
                    is PendingAction.MergePeople -> assistantStore.mergePeople(action.keepId, action.mergeId, System.currentTimeMillis())
                        .let { "Merged ${it.mergedName} into ${it.keptName}." }
                }
                setActionState(messageId, actionId, ActionState.DONE, outcome)
                scheduleSave()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                setActionState(messageId, actionId, ActionState.FAILED, error.message ?: "That could not be done. Nothing was changed.")
            } finally {
                setPhase(ChatPhase.Idle)
            }
        }
    }

    fun keepChatAction(messageId: Long, actionId: String) {
        setActionState(messageId, actionId, ActionState.KEPT, "Kept.")
    }

    fun startNewChat() {
        chatJob?.cancel()
        _chat.value = ChatState()
    }

    /**
     * Opens the report sheet for one assistant response.
     *
     * Only assistant turns can be reported, and only once: a second report on
     * the same message would duplicate the same evidence under a second label
     * without adding anything, so the thread stops offering it.
     */
    fun startFeedback(messageId: Long) {
        val message = _chat.value.messages.firstOrNull { it.id == messageId } ?: return
        if (message.role != ChatRole.ASSISTANT || message.reportedLabel != null) return
        _feedbackTarget.value = message
    }

    fun dismissFeedback() {
        _feedbackTarget.value = null
    }

    /**
     * Stores the report, copying the response into it as it goes.
     *
     * Nothing is sent as the report is saved. It sits in the local database and
     * leaves the phone only with the people's files in the notes repo.
     */
    fun submitFeedback(label: AiFeedbackLabel, note: String) {
        val message = _feedbackTarget.value ?: return
        _feedbackTarget.value = null
        viewModelScope.launch {
            try {
                repository.recordFeedback(
                    buildFeedback(
                        message = message,
                        userMessage = precedingUserMessage(message.id),
                        label = label,
                        note = note,
                        appVersion = installedAppVersion(context),
                    ),
                )
                updateMessage(message.id) { it.copy(reportedLabel = label.id) }
                showMessage("Reported. It is saved with your people in your notes repo.")
            } catch (error: Exception) {
                showMessage(error.message ?: "Could not save the report")
            }
        }
    }

    fun deleteFeedback(id: Long) {
        viewModelScope.launch {
            try {
                repository.deleteFeedback(id)
                saveStore.markSaveNeeded()
                refreshSaveState()
                scheduleSave()
            } catch (error: Exception) {
                showMessage(error.message ?: "Could not delete the report")
            }
        }
    }

    fun clearAllFeedback() {
        viewModelScope.launch {
            try {
                repository.clearFeedback()
                // Deleting reports changes what the next save should write.
                saveStore.markSaveNeeded()
                refreshSaveState()
                scheduleSave()
                showMessage("All assistant reports deleted")
            } catch (error: Exception) {
                showMessage(error.message ?: "Could not delete the reports")
            }
        }
    }

    fun revokeAssistantConsent() {
        gatewaySettings.setAssistantConsent(false)
        refreshGatewaySettingsState()
        showMessage("Assistant permission revoked")
    }

    fun saveNow() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { performSave(showSuccess = true) }
    }

    /** The save held back because it removes many people's files, once the user confirmed that they are gone. */
    fun saveRemovingPeople() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { performSave(showSuccess = true, allowRemovals = true) }
    }

    /** For test builds: whether this one writes the notes repo's people folder and takes its drafts. */
    fun setTestBuildOwnsFolder(owns: Boolean) {
        saveStore.testBuildOwnsFolder = owns
        refreshSaveState()
        refreshFolderPeople()
        checkInbox()
    }

    /** Fills an empty People from the files in the notes repo, as on a new phone. */
    fun loadFromLife() {
        if (_saveState.value.busy) return
        viewModelScope.launch {
            if (repository.snapshot().people.isNotEmpty()) {
                showMessage("People already has people, so nothing was loaded")
                return@launch
            }
            setSaveBusy(true)
            try {
                val loaded = peopleInLife.load()
                repository.replaceAll(loaded.snapshot, loaded.feedback)
                // Undo records would point at rows that were just replaced.
                startNewChat()
                showMessage("Loaded ${loaded.snapshot.people.size} people from your notes repo")
            } catch (e: Exception) {
                showMessage(e.message ?: "Could not load people from your notes repo")
            } finally {
                setSaveBusy(false)
            }
        }
    }

    private fun refreshFolderPeople() {
        viewModelScope.launch {
            val count = runCatching { peopleInLife.folderPeople() }.getOrNull()
            _saveState.value = _saveState.value.copy(folderPeople = count)
        }
    }

    private fun agentResult(messageId: Long): ChatAttachment.AgentResult? =
        _chat.value.messages.firstOrNull { it.id == messageId }?.attachment as? ChatAttachment.AgentResult

    private fun updateAgentResult(messageId: Long, transform: (ChatAttachment.AgentResult) -> ChatAttachment.AgentResult) =
        updateMessage(messageId) { message ->
            val result = message.attachment as? ChatAttachment.AgentResult ?: return@updateMessage message
            message.copy(attachment = transform(result))
        }

    private fun setActionState(messageId: Long, actionId: String, state: ActionState, outcome: String) =
        updateAgentResult(messageId) { result ->
            result.copy(
                pending = result.pending.map { item ->
                    if (item.action.id == actionId) item.copy(state = state, outcome = outcome) else item
                },
            )
        }

    private fun appendUser(text: String, photos: List<AttachedPhoto>) =
        append(ChatRole.USER, text, null, false, photos = photos)

    private fun appendAssistant(
        text: String,
        attachment: ChatAttachment? = null,
        failed: Boolean = false,
        fromGateway: Boolean = false,
    ) = append(ChatRole.ASSISTANT, text, attachment, failed, fromGateway)

    private fun append(
        role: ChatRole,
        text: String,
        attachment: ChatAttachment?,
        failed: Boolean,
        fromGateway: Boolean = false,
        photos: List<AttachedPhoto> = emptyList(),
    ) {
        val message = ChatMessage(
            id = nextMessageId++,
            role = role,
            text = text,
            attachment = attachment,
            failed = failed,
            sentAt = System.currentTimeMillis(),
            fromGateway = fromGateway,
            photoCount = photos.size,
            thumbnails = photos.map(AttachedPhoto::thumbnail),
        )
        _chat.value = _chat.value.copy(messages = _chat.value.messages + message)
    }

    private fun updateMessage(messageId: Long, transform: (ChatMessage) -> ChatMessage) {
        _chat.value = _chat.value.copy(
            messages = _chat.value.messages.map { if (it.id == messageId) transform(it) else it },
        )
    }

    /** The turn that produced a response, so a report shows both halves. */
    private fun precedingUserMessage(messageId: Long): String =
        _chat.value.messages.lastOrNull { it.id < messageId && it.role == ChatRole.USER }
            ?.let { AssistantPrompt.userLine(it.text, it.photoCount) }
            .orEmpty()

    private fun setPhase(phase: ChatPhase) {
        _chat.value = _chat.value.copy(phase = phase)
    }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            saveStore.markSaveNeeded()
            refreshSaveState()
            try {
                block()
                scheduleSave()
            } catch (e: Exception) {
                showMessage(e.message ?: "Could not save the change")
            }
        }
    }

    /** Saves to the notes repo shortly after a change, so a burst of changes is one commit. */
    private fun scheduleSave() {
        if (!peopleInLife.owns) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            performSave(showSuccess = false)
        }
    }

    private suspend fun performSave(showSuccess: Boolean, allowRemovals: Boolean = false) {
        if (_saveState.value.busy) return
        setSaveBusy(true)
        saveStore.markAttemptStarted()
        refreshSaveState()
        try {
            val people = peopleInLife.save(allowRemovals)
            saveStore.markSaved()
            _saveState.value = _saveState.value.copy(removalsWaiting = 0)
            refreshSaveState()
            refreshFolderPeople()
            if (showSuccess) showMessage("Saved $people people to your notes repo")
        } catch (e: Exception) {
            val safeMessage = e.message ?: "Saving to your notes repo failed"
            saveStore.markFailed(safeMessage)
            _saveState.value = _saveState.value.copy(removalsWaiting = (e as? PeopleRemovalException)?.removing ?: 0)
            refreshSaveState()
            if (showSuccess) showMessage(safeMessage)
        } finally {
            setSaveBusy(false)
        }
    }

    private fun setSaveBusy(busy: Boolean) {
        _saveState.value = _saveState.value.copy(busy = busy)
    }

    private fun refreshSaveState() {
        _saveState.value = _saveState.value.copy(status = saveStore.status, owns = peopleInLife.owns)
    }

    /**
     * Writes the unsent note to disk shortly after typing stops.
     *
     * Debounced because each write encrypts the whole draft, and a keystroke is
     * not worth that. Losing the last few hundred milliseconds of a note to a
     * process death is a far smaller loss than the whole note, which is what
     * happened before anything was persisted at all.
     */
    private fun persistDraft(value: String) {
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(DRAFT_SAVE_DELAY_MS)
            withContext(Dispatchers.IO) { runCatching { captureDraftStore.write(value) } }
        }
    }

    private suspend fun clearDraft() {
        draftSaveJob?.cancel()
        withContext(Dispatchers.IO) { runCatching { captureDraftStore.clear() } }
    }

    private fun refreshGatewaySettingsState() {
        _gatewaySettingsState.value = gatewaySettings.state
    }

    companion object {
        private const val SAVE_DELAY_MS = 1_500L
        private const val DRAFT_SAVE_DELAY_MS = 400L
        private const val UNREADABLE_PHOTO = "That photo could not be opened."
        private const val CHECKIN_REFRESH_MS = 60 * 60 * 1000L
    }
}
