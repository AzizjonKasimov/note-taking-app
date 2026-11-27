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
import com.azizjon.notes.people.backup.BackupStatus
import com.azizjon.notes.people.backup.GitHubBackupConfig
import com.azizjon.notes.people.backup.GitHubBackupSettings
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
import com.azizjon.notes.people.inbox.InboxCheck
import com.azizjon.notes.people.inbox.InboxDraft
import com.azizjon.notes.people.inbox.InboxState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class BackupUiState(
    val config: GitHubBackupConfig,
    val status: BackupStatus,
    val busy: Boolean = false,
)

data class FeedbackUiState(val reports: List<AiFeedbackEntity> = emptyList())

/** Bringing Network App's people into Notes, done once (Notes 2.5). */
sealed interface NetworkAppMove {
    data object NotChecked : NetworkAppMove

    /** Network App's backup opened with the saved passphrase, and holds this much. */
    data class Checked(val people: Int, val notes: Int, val reports: Int) : NetworkAppMove

    /** Its people are in Notes, and Notes' own first backup is saved and opens. */
    data class Done(val people: Int) : NetworkAppMove
}

class NetworkViewModel(application: Application) : AndroidViewModel(application) {
    private val app = (application as NotesApplication).people
    private val context: Application = application
    private val repository = app.repository
    private val settings = app.backupSettings
    private val backupManager = app.backupManager
    private val gatewaySettings = app.gatewaySettings
    private val assistantStore = app.assistantStore
    private val captureDraftStore = app.captureDraftStore
    private val pcInbox = app.pcInbox
    private val agent = AssistantAgent(
        client = app.agentClient,
        tools = AssistantTools(
            store = assistantStore,
            // Marked before the write rather than after the turn, so a process
            // killed mid-turn still leaves the backup flagged as due.
            beforeWrite = { settings.markBackupNeeded() },
        ),
    )

    val snapshot: StateFlow<NetworkSnapshot> = repository.observeSnapshot().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        NetworkSnapshot(),
    )

    private val _backupState = MutableStateFlow(BackupUiState(settings.config, settings.status))
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()
    private val _networkAppMove = MutableStateFlow<NetworkAppMove>(NetworkAppMove.NotChecked)
    val networkAppMove: StateFlow<NetworkAppMove> = _networkAppMove.asStateFlow()

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

    /** Whether drafts from the PC are on, with their key and last check, for Settings. */
    val inboxState: StateFlow<InboxState> = pcInbox.state

    /** True while the inbox is being turned on or off, or checked. */
    private val _inboxBusy = MutableStateFlow(false)
    val inboxBusy: StateFlow<Boolean> = _inboxBusy.asStateFlow()

    /** Drafts just sent, edited, or discarded, hidden while they are removed from the store. */
    private val _inboxHidden = MutableStateFlow<Set<String>>(emptySet())

    /** Drafts from the PC waiting on the Assistant tab, oldest first. */
    val inboxDrafts: StateFlow<List<InboxDraft>> = combine(pcInbox.state, _inboxHidden) { state, hidden ->
        state.pending.filterNot { it.id in hidden }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * One GitHub call at a time from this phone: the backup and the inbox both
     * commit to the data repository, and two commits racing on one branch can
     * fail with a conflict.
     */
    private val githubLock = Mutex()
    private var lastInboxCheckAt = 0L

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

    private var backupJob: Job? = null
    private var chatJob: Job? = null
    private var draftSaveJob: Job? = null
    private var nextMessageId = 1L

    init {
        if (settings.status.backupNeeded) scheduleAutoBackup()
        refreshCheckinSources()
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                runCatching { captureDraftStore.read() }.getOrDefault("")
            }
            // Never clobber something typed while the encrypted read was running.
            if (restored.isNotBlank() && _composerDraft.value.isBlank()) _composerDraft.value = restored
        }
        viewModelScope.launch(Dispatchers.IO) { runCatching { pcInbox.load() } }
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
    fun checkInbox(fromSettings: Boolean = false) {
        val now = System.currentTimeMillis()
        if (_inboxBusy.value || (!fromSettings && now - lastInboxCheckAt < INBOX_CHECK_INTERVAL_MS)) return
        lastInboxCheckAt = now
        viewModelScope.launch {
            val enabled = withContext(Dispatchers.IO) { runCatching { pcInbox.load().enabled }.getOrDefault(false) }
            if (!enabled) return@launch
            _inboxBusy.value = true
            try {
                val result = githubLock.withLock { withContext(Dispatchers.IO) { pcInbox.check(settings.config) } }
                val report = inboxReport(result)
                when {
                    report != null -> showMessage(report)
                    fromSettings && result.taken == 0 -> showMessage("No new drafts from your PC")
                    fromSettings -> showMessage("${result.taken} new draft(s) from your PC on the Assistant tab")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (fromSettings) showMessage(error.message ?: "Could not check for drafts from your PC")
            } finally {
                _inboxBusy.value = false
            }
        }
    }

    /** Turns drafts from the PC on or off, which publishes or removes this phone's key. */
    fun setInboxEnabled(enabled: Boolean) {
        if (_inboxBusy.value) return
        viewModelScope.launch {
            _inboxBusy.value = true
            try {
                githubLock.withLock {
                    withContext(Dispatchers.IO) {
                        val config = settings.config
                        if (enabled) pcInbox.enable(config) else pcInbox.disable(config)
                    }
                }
                showMessage(
                    if (enabled) {
                        "Drafts from your PC are on. This phone's key is ${pcInbox.state.value.key?.fingerprint}."
                    } else {
                        "Drafts from your PC are off"
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val reason = error.message ?: "GitHub could not be reached"
                showMessage(
                    if (enabled) {
                        "Could not turn on drafts from your PC: $reason"
                    } else {
                        "Turned off on this phone, but the inbox could not be cleared from GitHub: $reason"
                    },
                )
            } finally {
                _inboxBusy.value = false
            }
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

    /** Hides the draft at once, so a second tap cannot use it twice, while the store forgets it. */
    private fun removeInboxDraft(id: String) {
        _inboxHidden.value += id
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { pcInbox.take(id) } }
            _inboxHidden.value -= id
        }
    }

    /** What the user should hear about a look at the inbox, beyond the drafts on the card. */
    private fun inboxReport(result: InboxCheck): String? {
        val parts = buildList {
            if (result.keyRestored) {
                add("This phone's inbox key was missing from the data repository or had been replaced, so the app put it back.")
            }
            if (result.removed.isNotEmpty()) {
                val reasons = result.removed.distinct().joinToString("; ")
                add(
                    if (result.removed.size == 1) {
                        "A draft from your PC could not be opened and was deleted: $reasons."
                    } else {
                        "${result.removed.size} drafts from your PC could not be opened and were deleted: $reasons."
                    },
                )
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
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

    // Record answers change the network, so they go through mutate() and the backup marker.
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
                    refreshBackupState()
                    scheduleAutoBackup()
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
            settings.markBackupNeeded()
            refreshBackupState()
            try {
                assistantStore.undo(result.changes, System.currentTimeMillis())
                updateAgentResult(messageId) { it.copy(undone = true) }
                appendAssistant("Undone. Everything that reply saved is back the way it was.")
                scheduleAutoBackup()
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
            settings.markBackupNeeded()
            refreshBackupState()
            try {
                val outcome = when (val action = item.action) {
                    is PendingAction.DeletePerson -> "Deleted ${assistantStore.deletePerson(action.personId)}."
                    is PendingAction.DeleteNote -> assistantStore.deleteNote(action.noteId).let { "Deleted the note." }
                    is PendingAction.DeleteRecord -> assistantStore.deleteRecord(action.ref).let { "Deleted." }
                    is PendingAction.MergePeople -> assistantStore.mergePeople(action.keepId, action.mergeId, System.currentTimeMillis())
                        .let { "Merged ${it.mergedName} into ${it.keptName}." }
                }
                setActionState(messageId, actionId, ActionState.DONE, outcome)
                scheduleAutoBackup()
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
     * leaves only inside the next encrypted backup, which the user starts.
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
                showMessage("Reported. It travels with your next encrypted backup.")
            } catch (error: Exception) {
                showMessage(error.message ?: "Could not save the report")
            }
        }
    }

    fun deleteFeedback(id: Long) {
        viewModelScope.launch {
            try {
                repository.deleteFeedback(id)
                settings.markBackupNeeded()
                refreshBackupState()
                scheduleAutoBackup()
            } catch (error: Exception) {
                showMessage(error.message ?: "Could not delete the report")
            }
        }
    }

    fun clearAllFeedback() {
        viewModelScope.launch {
            try {
                repository.clearFeedback()
                // Deleting reports changes what the next backup should contain.
                settings.markBackupNeeded()
                refreshBackupState()
                scheduleAutoBackup()
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

    fun saveBackupConfig(config: GitHubBackupConfig) {
        settings.save(config)
        refreshBackupState()
        showMessage("Backup settings saved")
        if (config.autoBackup && settings.status.backupNeeded) scheduleAutoBackup()
    }

    fun backupNow() {
        backupJob?.cancel()
        backupJob = viewModelScope.launch { performBackup(showSuccess = true) }
    }

    /**
     * Checks that the saved passphrase opens the stored backup, changing nothing.
     *
     * Deliberately separate from Restore. The question "can I still get my data
     * back" should be answerable without the destructive action that answers it
     * by accident, and a passphrase that has drifted out of sync stays invisible
     * until then.
     */
    fun verifyBackup() {
        if (_backupState.value.busy) return
        viewModelScope.launch {
            val config = settings.config
            if (!config.configured) {
                showMessage("Complete and save the GitHub backup settings first")
                return@launch
            }
            setBackupBusy(true)
            try {
                val check = githubLock.withLock { backupManager.verify(config) }
                val reports = if (check.feedback == 0) "" else ", ${check.feedback} report(s)"
                showMessage(
                    "Passphrase opens the stored backup: ${check.people} people, " +
                        "${check.interactions} notes$reports",
                )
            } catch (e: Exception) {
                // Left off the backup status on purpose: nothing was attempted,
                // so a failed check must not make a good backup look broken.
                showMessage(e.message ?: "The stored backup could not be checked")
            } finally {
                setBackupBusy(false)
            }
        }
    }

    fun restoreFromGitHub(onRestored: () -> Unit = {}) {
        if (_backupState.value.busy) return
        viewModelScope.launch {
            val config = settings.config
            if (!config.configured) {
                showMessage("Complete and save the GitHub backup settings first")
                return@launch
            }
            setBackupBusy(true)
            settings.markAttemptStarted()
            refreshBackupState()
            try {
                val restored = githubLock.withLock { backupManager.download(config) }
                settings.markBackupNeeded()
                repository.replaceAll(restored.snapshot, restored.feedback)
                settings.markBackedUp()
                refreshBackupState()
                // Undo records point at rows the restore just replaced.
                startNewChat()
                showMessage("Restored ${restored.snapshot.people.size} people from encrypted backup")
                onRestored()
            } catch (e: Exception) {
                val safeMessage = e.message ?: "Restore failed"
                settings.markFailed(safeMessage)
                refreshBackupState()
                showMessage(safeMessage)
            } finally {
                setBackupBusy(false)
            }
        }
    }

    /**
     * Network App's backup: the same private repository, token, and passphrase as Notes' own backup,
     * but Network App's file. Null, with a message, until the settings allow reading it.
     */
    private fun networkAppConfig(): GitHubBackupConfig? {
        val config = settings.config
        if (!config.configured) {
            showMessage("Save the backup settings first: the GitHub token and Network App's passphrase")
            return null
        }
        if (config.path == GitHubBackupSettings.NETWORK_APP_PATH) {
            // Notes writing there would replace Network App's backup.
            showMessage("Change the backup path first: ${config.path} is Network App's own file")
            return null
        }
        return config.copy(path = GitHubBackupSettings.NETWORK_APP_PATH)
    }

    /** Opens Network App's backup with the saved passphrase and says what it holds; changes nothing. */
    fun checkNetworkAppBackup() {
        if (_backupState.value.busy) return
        val config = networkAppConfig() ?: return
        viewModelScope.launch {
            setBackupBusy(true)
            try {
                val check = githubLock.withLock { backupManager.verify(config) }
                _networkAppMove.value = NetworkAppMove.Checked(check.people, check.interactions, check.feedback)
            } catch (e: Exception) {
                showMessage(e.message ?: "Network App's backup could not be read")
            } finally {
                setBackupBusy(false)
            }
        }
    }

    /**
     * Puts Network App's people into Notes, then saves Notes' own first backup to its own file and
     * checks that it opens. Never runs once Notes has people, so it cannot replace edits made here.
     */
    fun bringPeopleFromNetworkApp() {
        if (_backupState.value.busy || _networkAppMove.value !is NetworkAppMove.Checked) return
        val from = networkAppConfig() ?: return
        viewModelScope.launch {
            if (repository.snapshot().people.isNotEmpty()) {
                showMessage("Notes already has people, so nothing was brought in")
                return@launch
            }
            setBackupBusy(true)
            settings.markAttemptStarted()
            refreshBackupState()
            try {
                val restored = githubLock.withLock { backupManager.download(from) }
                settings.markBackupNeeded()
                repository.replaceAll(restored.snapshot, restored.feedback)
                val config = settings.config
                githubLock.withLock {
                    backupManager.backup(config)
                    backupManager.verify(config)
                }
                refreshBackupState()
                // Undo records would point at rows that were just replaced.
                startNewChat()
                _networkAppMove.value = NetworkAppMove.Done(restored.snapshot.people.size)
                showMessage("${restored.snapshot.people.size} people are in Notes now, and Notes' own backup is saved")
            } catch (e: Exception) {
                val safeMessage = e.message ?: "Bringing the people in failed"
                settings.markFailed(safeMessage)
                refreshBackupState()
                showMessage(safeMessage)
            } finally {
                setBackupBusy(false)
            }
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
            settings.markBackupNeeded()
            refreshBackupState()
            try {
                block()
                scheduleAutoBackup()
            } catch (e: Exception) {
                showMessage(e.message ?: "Could not save the change")
            }
        }
    }

    private fun scheduleAutoBackup() {
        val config = settings.config
        if (!config.autoBackup || !config.configured) return
        backupJob?.cancel()
        backupJob = viewModelScope.launch {
            delay(AUTO_BACKUP_DELAY_MS)
            performBackup(showSuccess = false)
        }
    }

    private suspend fun performBackup(showSuccess: Boolean) {
        if (_backupState.value.busy) return
        val config = settings.config
        if (!config.configured) {
            if (showSuccess) showMessage("Complete and save the GitHub backup settings first")
            return
        }
        setBackupBusy(true)
        settings.markAttemptStarted()
        refreshBackupState()
        try {
            val result = githubLock.withLock { backupManager.backup(config) }
            refreshBackupState()
            if (showSuccess) {
                val reports = if (result.feedback == 0) "" else " and ${result.feedback} assistant report(s)"
                showMessage("Encrypted backup saved for ${result.people} people$reports")
            }
        } catch (e: Exception) {
            val safeMessage = e.message ?: "GitHub backup failed"
            settings.markFailed(safeMessage)
            refreshBackupState()
            showMessage(safeMessage)
        } finally {
            setBackupBusy(false)
        }
    }

    private fun setBackupBusy(busy: Boolean) {
        _backupState.value = _backupState.value.copy(busy = busy)
    }

    private fun refreshBackupState() {
        _backupState.value = _backupState.value.copy(config = settings.config, status = settings.status)
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
        private const val AUTO_BACKUP_DELAY_MS = 1_500L
        private const val DRAFT_SAVE_DELAY_MS = 400L
        private const val INBOX_CHECK_INTERVAL_MS = 15_000L
        private const val UNREADABLE_PHOTO = "That photo could not be opened."
        private const val CHECKIN_REFRESH_MS = 60 * 60 * 1000L
    }
}
