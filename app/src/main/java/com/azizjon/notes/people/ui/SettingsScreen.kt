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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.azizjon.notes.people.backup.GitHubBackupConfig
import com.azizjon.notes.people.backup.GitHubBackupSettings
import com.azizjon.notes.people.ai.GatewaySettingsState
import com.azizjon.notes.people.feedback.AiFeedbackLabel
import com.azizjon.notes.people.inbox.InboxState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    backupState: BackupUiState,
    gatewaySettingsState: GatewaySettingsState,
    feedbackState: FeedbackUiState,
    inboxState: InboxState,
    inboxBusy: Boolean,
    peopleCount: Int,
    networkAppMove: NetworkAppMove,
    networkAppHandover: Int?,
    onCheckNetworkApp: () -> Unit,
    onBringFromNetworkApp: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRevokeAssistantConsent: () -> Unit,
    onSaveBackupConfig: (GitHubBackupConfig) -> Unit,
    onBackupNow: () -> Unit,
    onVerifyBackup: () -> Unit,
    onRestore: (() -> Unit) -> Unit,
    onDeleteFeedback: (Long) -> Unit,
    onClearFeedback: () -> Unit,
    onSetInboxEnabled: (Boolean) -> Unit,
    onCheckInbox: () -> Unit,
) {
    val saved = backupState.config
    var owner by remember(saved) { mutableStateOf(saved.owner) }
    var repo by remember(saved) { mutableStateOf(saved.repo) }
    var branch by remember(saved) { mutableStateOf(saved.branch) }
    var path by remember(saved) { mutableStateOf(saved.path) }
    var token by remember(saved) { mutableStateOf(saved.token) }
    var passphrase by remember(saved) { mutableStateOf(saved.passphrase) }
    var autoBackup by remember(saved) { mutableStateOf(saved.autoBackup) }
    var confirmRestore by rememberSaveable { mutableStateOf(false) }
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
            Text("Encrypted GitHub backup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "The app encrypts all network data on-device before upload and refuses public repositories. The token and passphrase stay in Android encrypted preferences and are never included in the backup.",
                style = MaterialTheme.typography.bodyMedium,
            )
            BackupStatusCard(backupState)
            SettingsField(owner, { owner = it }, "GitHub owner")
            SettingsField(repo, { repo = it }, "Private data repository")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsField(branch, { branch = it }, "Branch", Modifier.weight(1f))
                SettingsField(path, { path = it }, "Backup path", Modifier.weight(2f))
            }
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("GitHub fine-grained token") },
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("Grant Contents read/write only to the private data repository") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Backup encryption passphrase") },
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("At least 12 characters; you will need it after reinstalling") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(autoBackup, { autoBackup = it })
                Text("Automatically back up after changes")
            }
            Button(
                onClick = {
                    onSaveBackupConfig(
                        GitHubBackupConfig(owner, repo, branch, path, token, passphrase, autoBackup),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save backup settings") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !backupState.busy, onClick = onBackupNow, modifier = Modifier.weight(1f)) {
                    Text(if (backupState.busy) "Working…" else "Back up now")
                }
                Button(enabled = !backupState.busy, onClick = { confirmRestore = true }, modifier = Modifier.weight(1f)) {
                    Text("Restore")
                }
            }
            OutlinedButton(
                enabled = !backupState.busy,
                onClick = onVerifyBackup,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Check the stored backup opens") }
            Text(
                "Tries the saved passphrase against the backup in the repository and restores nothing. A passphrase that has drifted out of sync is invisible until a restore, which is the one moment there is no second copy to fall back on. Worth doing after changing it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Offered only while Notes has no people, so it can never replace edits made here.
            if (peopleCount == 0 || networkAppMove is NetworkAppMove.Done) {
                Spacer(Modifier.height(8.dp))
                Text("Bring your people from Network App", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                NetworkAppMoveCard(
                    move = networkAppMove,
                    handover = networkAppHandover,
                    busy = backupState.busy,
                    backupConfigured = saved.configured,
                    onCheck = onCheckNetworkApp,
                    onBring = onBringFromNetworkApp,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text("Drafts from your PC", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Lets Claude sessions on your PC queue messages for the Assistant. Each draft is encrypted on the PC so only this phone can open it, waits in the private data repository, and is deleted there as soon as this phone takes it. Drafts wait on the Assistant tab, and nothing reaches the assistant until you tap Send.",
                style = MaterialTheme.typography.bodyMedium,
            )
            InboxCard(
                state = inboxState,
                busy = inboxBusy,
                backupConfigured = saved.configured,
                repository = "${saved.owner}/${saved.repo}",
                onSetEnabled = onSetInboxEnabled,
                onCheck = onCheckInbox,
            )

            Spacer(Modifier.height(8.dp))
            Text("Assistant feedback", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Report a wrong answer from the Assistant tab and it is stored here with a copy of the message and the response. Reports travel inside the encrypted backup, so backing up is all it takes to hand them over. Delete one once its fault has been fixed.",
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
            text = { Text("Every stored report is deleted. Your people and records are not affected. This cannot be undone, and the next backup will no longer carry them.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearFeedback = false
                    onClearFeedback()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmClearFeedback = false }) { Text("Cancel") } },
        )
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Replace local network data?") },
            text = { Text("Restore decrypts the GitHub backup and replaces every local person and linked record. This cannot be undone unless the current data is backed up first.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    onRestore {}
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
        )
    }
}

/**
 * What has been reported, and the one thing left to do with it.
 *
 * The list is deliberately shown in full rather than summarised: a report is
 * only useful if the owner can still recognise it, and recognising it is also
 * how a mistaken one gets deleted before it reaches a backup.
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

/**
 * Off until turned on, because turning it on publishes this phone's public key
 * to the data repository, and the PC script trusts the key it first finds there.
 */
/** The one-time move of Network App's people into Notes: check its backup, then bring them in. */
@Composable
private fun NetworkAppMoveCard(
    move: NetworkAppMove,
    handover: Int?,
    busy: Boolean,
    backupConfigured: Boolean,
    onCheck: () -> Unit,
    onBring: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (move) {
                NetworkAppMove.NotChecked -> {
                    if (handover == null) {
                        Text(
                            "First, in Network App, tap \"Move to Notes\". It saves one last backup and gives its check-in answers to Notes, so nothing is left behind.",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            "Network App has moved: it gave Notes $handover check-in answers and saved its last backup.",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        "Then save the backup settings above: a GitHub token for the private data repository, and Network App's passphrase.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Notes opens Network App's last backup (${GitHubBackupSettings.NETWORK_APP_PATH}) and shows what it holds. Nothing changes yet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(enabled = backupConfigured && !busy, onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "Working…" else "Check Network App's backup")
                    }
                }
                is NetworkAppMove.Checked -> {
                    val reports = if (move.reports == 0) "" else ", and ${move.reports} report(s)"
                    Text("It holds ${move.people} people and ${move.notes} notes$reports.", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Bringing them in puts them here, then saves Notes' own first backup to ${GitHubBackupSettings.DEFAULT_PATH}. Network App's file stays as it is.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(enabled = !busy, onClick = onBring, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "Working…" else "Bring them into Notes")
                    }
                }
                is NetworkAppMove.Done -> {
                    Text("${move.people} people are in Notes now.", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Notes' own backup is saved and opens. Turn on drafts from your PC below, and the check-in's sources on its tab.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun InboxCard(
    state: InboxState,
    busy: Boolean,
    backupConfigured: Boolean,
    repository: String,
    onSetEnabled: (Boolean) -> Unit,
    onCheck: () -> Unit,
) {
    val key = state.key
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Receive drafts from your PC", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = key != null,
                    onCheckedChange = onSetEnabled,
                    enabled = !busy && (key != null || backupConfigured),
                )
            }
            if (key == null) {
                Text(
                    if (backupConfigured) {
                        "Turning this on makes a key pair on this phone and puts its public half in the data repository. The private half never leaves the phone and is not in the backup."
                    } else {
                        "Save the GitHub backup settings above first."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("This phone's key: ${key.fingerprint}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "The PC script shows the key the first time it queues a draft, and it should match this one. Turning this off deletes the key, and anything still queued can no longer be opened.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Drafts arrive in ${key.folder}/ in $repository.", style = MaterialTheme.typography.bodySmall)
                when {
                    busy -> Text("Working…", style = MaterialTheme.typography.bodySmall)
                    state.lastError.isNotBlank() -> Text(
                        "Last check failed: ${state.lastError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    state.lastCheckAt > 0 -> Text("Last checked ${formatTimestamp(state.lastCheckAt)}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onCheck, enabled = !busy) { Text("Check now") }
            }
        }
    }
}

@Composable
private fun BackupStatusCard(state: BackupUiState) {
    val status = state.status
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when {
                    status.backupNeeded -> "Backup required"
                    status.lastBackupAt > 0 -> "Local data is backed up"
                    else -> "No successful backup yet"
                },
                fontWeight = FontWeight.SemiBold,
                color = if (status.backupNeeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            if (status.lastBackupAt > 0) Text("Last success: ${formatTimestamp(status.lastBackupAt)}", style = MaterialTheme.typography.bodySmall)
            if (status.lastAttemptAt > 0) Text("Last attempt: ${formatTimestamp(status.lastAttemptAt)}", style = MaterialTheme.typography.bodySmall)
            if (status.lastError.isNotBlank()) Text(status.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(value, onValueChange, modifier = modifier, label = { Text(label) }, singleLine = true)
}

private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
internal fun formatTimestamp(value: Long): String =
    Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(timestampFormatter)
