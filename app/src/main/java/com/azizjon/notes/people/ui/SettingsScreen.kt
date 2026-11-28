package com.azizjon.notes.people.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.azizjon.notes.BuildConfig
import com.azizjon.notes.people.ai.GatewaySettingsState
import com.azizjon.notes.people.feedback.AiFeedbackLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    saveState: SaveUiState,
    gatewaySettingsState: GatewaySettingsState,
    feedbackState: FeedbackUiState,
    peopleCount: Int,
    movedFromNetworkApp: Int?,
    onOpenAppSettings: () -> Unit,
    onRevokeAssistantConsent: () -> Unit,
    onSaveNow: () -> Unit,
    onSaveRemovingPeople: () -> Unit,
    onLoadFromLife: () -> Unit,
    onSetTestBuildOwnsFolder: (Boolean) -> Unit,
    onDeleteFeedback: (Long) -> Unit,
    onClearFeedback: () -> Unit,
) {
    var confirmClearFeedback by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("People settings") })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Assistant", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "The assistant reads the records it needs to answer or to file what you tell it, never contact values. What it saves can be undone from its reply; deleting and merging wait for your confirmation. Manual editing and local search remain available offline.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (gatewaySettingsState.tokenSaved) "AI key saved" else "AI key needed",
                        fontWeight = FontWeight.SemiBold,
                        color = if (gatewaySettingsState.tokenSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "It uses the same AI key as the \"tell me anything\" box, from Settings → Assistant.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onOpenAppSettings) { Text("Open Settings") }
                    HorizontalDivider()
                    Text(
                        if (gatewaySettingsState.assistantConsent) "The assistant may read your people" else "The assistant has not been allowed to read your people yet",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "It reads only what a request needs, through the gateway to Anthropic. It never searches the web. Contact values never leave this phone.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (gatewaySettingsState.assistantConsent) {
                        TextButton(onClick = onRevokeAssistantConsent) { Text("Revoke permission") }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Saved in your notes repo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "A few seconds after each change, this phone writes your people into the people folder of your notes repo, one file for each person, and the next sync sends them to GitHub. They are a copy: change people here, not in those files. Contact values stay only on this phone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            SaveCard(
                state = saveState,
                peopleCount = peopleCount,
                movedFromNetworkApp = movedFromNetworkApp,
                onSaveNow = onSaveNow,
                onSaveRemovingPeople = onSaveRemovingPeople,
                onLoadFromLife = onLoadFromLife,
                onSetTestBuildOwnsFolder = onSetTestBuildOwnsFolder,
            )

            Spacer(Modifier.height(8.dp))
            Text("Drafts from your PC", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Claude sessions on your PC can leave a message for the assistant in the people folder's drafts. It waits above the message box on the Assistant tab, and nothing reaches the assistant until you tap Send.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(8.dp))
            Text("Assistant feedback", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Report a wrong answer from the Assistant tab and it is stored here with a copy of the message and the response. Reports are saved with your people in the notes repo (people/reports.json). Delete one once its fault has been fixed.",
                style = MaterialTheme.typography.bodyMedium,
            )
            FeedbackCard(
                state = feedbackState,
                onClear = { confirmClearFeedback = true },
                onDelete = onDeleteFeedback,
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmClearFeedback) {
        AlertDialog(
            onDismissRequest = { confirmClearFeedback = false },
            title = { Text("Delete all reports?") },
            text = { Text("Every stored report is deleted. Your people and records are not affected. This cannot be undone, and the next save removes them from your notes repo too.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearFeedback = false
                    onClearFeedback()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmClearFeedback = false }) { Text("Cancel") } },
        )
    }
}

private fun peopleCount(n: Int): String = if (n == 1) "1 person" else "$n people"

/** Where People's copy in the notes repo stands, and the ways to fill an empty People. */
@Composable
private fun SaveCard(
    state: SaveUiState,
    peopleCount: Int,
    movedFromNetworkApp: Int?,
    onSaveNow: () -> Unit,
    onSaveRemovingPeople: () -> Unit,
    onLoadFromLife: () -> Unit,
    onSetTestBuildOwnsFolder: (Boolean) -> Unit,
) {
    val status = state.status
    var confirmRemoval by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (BuildConfig.DEBUG) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This test build writes the people folder", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = state.owns, onCheckedChange = onSetTestBuildOwnsFolder, enabled = !state.busy)
                }
                Text(
                    "Off on your phone: there the released Notes owns the folder, and a test build writing it would replace your people's files. Loading from the folder works either way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
            }
            if (movedFromNetworkApp != null) {
                Text("${peopleCount(movedFromNetworkApp)} came over from Network App.", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
            if (state.owns) {
                Text(
                    when {
                        peopleCount == 0 -> "Nothing to save yet"
                        status.saveNeeded -> "Changes waiting to be saved"
                        status.lastSavedAt > 0 -> "Saved"
                        else -> "Not saved yet"
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = if (status.saveNeeded && peopleCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                if (status.lastSavedAt > 0) Text("Last saved: ${formatTimestamp(status.lastSavedAt)}", style = MaterialTheme.typography.bodySmall)
                if (status.lastError.isNotBlank()) Text(status.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                state.folderPeople?.let { Text("The people folder holds ${if (it == 1) "1 person's file" else "$it people's files"}.", style = MaterialTheme.typography.bodySmall) }
                Button(enabled = !state.busy && peopleCount > 0, onClick = onSaveNow, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.busy) "Working…" else "Save now")
                }
                if (state.removalsWaiting > 0) {
                    OutlinedButton(enabled = !state.busy, onClick = { confirmRemoval = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Remove ${state.removalsWaiting} people's files and save")
                    }
                }
            }
            if (peopleCount == 0) {
                val inFolder = state.folderPeople ?: 0
                Text(
                    if (inFolder > 0) {
                        "Your notes repo holds ${peopleCount(inFolder)}. Load them here, as on a new phone. Contact values are not in the files, so they stay empty."
                    } else {
                        "To bring your people over from Network App, open Network App and tap \"Move to Notes\". It hands everything to Notes on this phone."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (inFolder > 0) {
                    OutlinedButton(enabled = !state.busy, onClick = onLoadFromLife, modifier = Modifier.fillMaxWidth()) {
                        Text("Load people from your notes repo")
                    }
                }
            }
        }
    }

    if (confirmRemoval) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text("Remove ${state.removalsWaiting} people's files?") },
            text = { Text("Your notes repo has files for ${state.removalsWaiting} people who are not in People on this phone. Remove them only if you deleted those people on purpose. The repo's history keeps the old files.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoval = false
                    onSaveRemovingPeople()
                }) { Text("Remove and save") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("Cancel") } },
        )
    }
}

/**
 * What has been reported, and the one thing left to do with it.
 *
 * The list is deliberately shown in full rather than summarised: a report is
 * only useful if the owner can still recognise it, and recognising it is also
 * how a mistaken one gets deleted before it is saved to the notes repo.
 */
@Composable
private fun FeedbackCard(
    state: FeedbackUiState,
    onClear: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (state.reports.isEmpty()) "No reports yet" else "${state.reports.size} report(s) collected",
                fontWeight = FontWeight.SemiBold,
            )
            Button(
                enabled = state.reports.isNotEmpty(),
                onClick = onClear,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Delete all") }
            state.reports.forEach { report ->
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            AiFeedbackLabel.titleFor(report.label),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${report.stage} · ${formatTimestamp(report.createdAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (report.note.isNotBlank()) {
                            Text(report.note, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = { onDelete(report.id) }) { Text("Delete") }
                }
            }
        }
    }
}

private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
internal fun formatTimestamp(value: Long): String =
    Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(timestampFormatter)
