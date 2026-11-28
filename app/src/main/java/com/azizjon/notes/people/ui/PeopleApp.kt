package com.azizjon.notes.people.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

enum class AppSection(val label: String) {
    CHAT("Assistant"),
    CHECKIN("Check-in"),
    PEOPLE("People"),
    NEEDS("Needs"),
    SETTINGS("Settings"),
}

/**
 * The People tab: what Network App was until Notes 2.5, with its five sections in a row at the top
 * and the app's own tabs ([bottomBar]) at the bottom. The view model belongs to the activity, so the
 * assistant's conversation lives on while the user is on Today or in the notes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleApp(
    viewModel: NetworkViewModel,
    bottomBar: @Composable () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val composerDraft by viewModel.composerDraft.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val preparingPhotos by viewModel.preparingPhotos.collectAsStateWithLifecycle()
    val composerFocusRequested by viewModel.composerFocusRequested.collectAsStateWithLifecycle()
    val consentRequested by viewModel.consentRequested.collectAsStateWithLifecycle()
    val gatewaySettingsState by viewModel.gatewaySettingsState.collectAsStateWithLifecycle()
    val feedbackState by viewModel.feedbackState.collectAsStateWithLifecycle()
    val feedbackTarget by viewModel.feedbackTarget.collectAsStateWithLifecycle()
    val checkinList by viewModel.checkinList.collectAsStateWithLifecycle()
    val checkinSettings by viewModel.checkinSettings.collectAsStateWithLifecycle()
    val requestedSection by viewModel.requestedSection.collectAsStateWithLifecycle()
    val inboxDrafts by viewModel.inboxDrafts.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val movedFromNetworkApp by viewModel.movedFromNetworkApp.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var section by rememberSaveable { mutableStateOf(AppSection.CHAT) }
    var selectedPersonId by rememberSaveable { mutableStateOf<Long?>(null) }
    val sectionState = rememberSaveableStateHolder()

    LaunchedEffect(message) {
        val value = message ?: return@LaunchedEffect
        snackbar.showSnackbar(value)
        viewModel.clearMessage()
    }

    // A shared photo, a notification, or a check-in answer can ask for a section.
    LaunchedEffect(requestedSection) {
        val target = requestedSection ?: return@LaunchedEffect
        selectedPersonId = null
        section = target
        viewModel.clearRequestedSection()
    }

    // Opening the Assistant looks for drafts from the PC too, not only coming
    // back to the app; the check spaces itself out.
    LaunchedEffect(section) {
        if (section == AppSection.CHAT) viewModel.checkInbox()
    }

    // Back from a person returns to the list they were opened from instead of
    // closing the app.
    BackHandler(enabled = selectedPersonId != null) { selectedPersonId = null }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selectedPersonId == null) {
                Surface(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                    PrimaryScrollableTabRow(selectedTabIndex = section.ordinal, edgePadding = 8.dp) {
                        AppSection.entries.forEach { item ->
                            val count = when (item) {
                                AppSection.CHECKIN -> checkinList.count
                                AppSection.CHAT -> inboxDrafts.size
                                else -> 0
                            }
                            Tab(
                                selected = section == item,
                                onClick = { section = item },
                                text = {
                                    BadgedBox(badge = { if (count > 0) Badge { Text(if (count > 99) "99+" else count.toString()) } }) {
                                        Text(item.label, maxLines = 1)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
        bottomBar = { if (selectedPersonId == null) bottomBar() },
    ) { padding ->
        // Each section's own top bar would add the status bar's height again; the tab row has it.
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            val selected = selectedPersonId?.let(snapshot::person)
            if (selected != null) {
                PersonDetailScreen(
                    person = selected,
                    snapshot = snapshot,
                    onBack = { selectedPersonId = null },
                    onSave = viewModel::savePerson,
                    onDeletePerson = { person ->
                        viewModel.deletePerson(person) { selectedPersonId = null }
                    },
                    onAddInteraction = viewModel::addInteraction,
                    onAddNeed = viewModel::addNeed,
                    onAddCapability = viewModel::addCapability,
                    onAddAffiliation = viewModel::addAffiliation,
                    onAddFact = viewModel::addFact,
                    onDeleteInteraction = viewModel::deleteInteraction,
                    onMoveInteraction = { interactionId, destination ->
                        // Follow the note to its new home: seeing it land is the
                        // only confirmation that the right person was chosen.
                        viewModel.moveInteraction(interactionId, destination) { selectedPersonId = it }
                    },
                    onDeleteNeed = viewModel::deleteNeed,
                    onDeleteCapability = viewModel::deleteCapability,
                    onDeleteAffiliation = viewModel::deleteAffiliation,
                    onSetAffiliationCurrent = viewModel::setAffiliationCurrent,
                    onDeleteFact = viewModel::deleteFact,
                )
            } else {
                // Each section keeps its list, filter, and scroll position while a
                // person is open, so Back lands where the user left off.
                sectionState.SaveableStateProvider(section.name) {
                    when (section) {
                        AppSection.CHAT -> ChatScreen(
                            chat = chat,
                            snapshot = snapshot,
                            draft = composerDraft,
                            photos = photos,
                            preparingPhotos = preparingPhotos,
                            focusRequested = composerFocusRequested,
                            consentRequested = consentRequested,
                            feedbackTarget = feedbackTarget,
                            inboxDrafts = inboxDrafts,
                            onDraftChange = viewModel::updateComposerDraft,
                            onAttachPhotos = viewModel::attachPhotos,
                            onRemovePhoto = viewModel::removePhoto,
                            newCaptureUri = viewModel::newCaptureUri,
                            onFocused = viewModel::composerFocused,
                            onSend = viewModel::sendChatMessage,
                            onOpenPerson = { selectedPersonId = it },
                            onUndo = viewModel::undoChatChanges,
                            onConfirmAction = viewModel::confirmChatAction,
                            onKeepAction = viewModel::keepChatAction,
                            onConfirmConsent = viewModel::confirmAssistantConsent,
                            onDismissConsent = viewModel::dismissAssistantConsent,
                            onReportMessage = viewModel::startFeedback,
                            onDismissFeedback = viewModel::dismissFeedback,
                            onSubmitFeedback = viewModel::submitFeedback,
                            onNewChat = viewModel::startNewChat,
                            onSendInboxDraft = viewModel::sendInboxDraft,
                            onEditInboxDraft = viewModel::editInboxDraft,
                            onDiscardInboxDraft = viewModel::discardInboxDraft,
                        )
                        AppSection.CHECKIN -> CheckinScreen(
                            list = checkinList,
                            snapshot = snapshot,
                            settings = checkinSettings,
                            onOpenPerson = { selectedPersonId = it },
                            onAddPerson = viewModel::checkinAddPerson,
                            onLinkPerson = viewModel::checkinLinkPerson,
                            onAddNote = viewModel::checkinAddNote,
                            onPeopleStatus = viewModel::setCheckinStatus,
                            onSkipAllNew = viewModel::skipAllNewPeople,
                            onNeedStillOpen = viewModel::confirmNeedStillOpen,
                            onNeedDone = viewModel::closeNeedFromCheckin,
                            onPositionStill = viewModel::confirmPosition,
                            onPositionLeft = viewModel::endPosition,
                            onRecordStatus = viewModel::setRecordCheckinStatus,
                            onSetEvening = viewModel::setEveningReminder,
                            onSetContacts = viewModel::setContactsSource,
                            onSetChats = viewModel::setChatsSource,
                            onRefresh = viewModel::refreshCheckinSources,
                        )
                        AppSection.PEOPLE -> PeopleScreen(
                            snapshot = snapshot,
                            onOpenPerson = { selectedPersonId = it },
                            onSavePerson = viewModel::savePerson,
                        )
                        AppSection.NEEDS -> NeedsScreen(
                            snapshot = snapshot,
                            onOpenPerson = { selectedPersonId = it },
                            onSetHelped = viewModel::setNeedHelped,
                            onSetActive = viewModel::setNeedActive,
                        )
                        AppSection.SETTINGS -> SettingsScreen(
                            saveState = saveState,
                            gatewaySettingsState = gatewaySettingsState,
                            feedbackState = feedbackState,
                            peopleCount = snapshot.people.size,
                            movedFromNetworkApp = movedFromNetworkApp,
                            onOpenAppSettings = onOpenAppSettings,
                            onRevokeAssistantConsent = viewModel::revokeAssistantConsent,
                            onSaveNow = viewModel::saveNow,
                            onSaveRemovingPeople = viewModel::saveRemovingPeople,
                            onLoadFromLife = viewModel::loadFromLife,
                            onSetTestBuildOwnsFolder = viewModel::setTestBuildOwnsFolder,
                            onDeleteFeedback = viewModel::deleteFeedback,
                            onClearFeedback = viewModel::clearAllFeedback,
                        )
                    }
                }
            }
        }
    }
}
