package com.azizjon.notes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azizjon.notes.ui.theme.AppThemeMode
import kotlinx.coroutines.launch
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: NotesViewModel,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    val sync by viewModel.syncStatus.collectAsStateWithLifecycle()
    var showTokenDialog by remember { mutableStateOf(false) }
    /** Null while hidden; otherwise whether the phone holds changes GitHub lacks. */
    var disconnectWarning by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        AppThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = { onThemeModeChange(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = AppThemeMode.entries.size),
                            ) { Text(mode.label) }
                        }
                    }
                }
            }

            ReminderSettingsCard()

            VoiceSettingsCard()

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Your life repo", style = MaterialTheme.typography.titleMedium)
                    Text(
                        viewModel.repoName ?: "Unknown repo",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (sync.lastSyncAt > 0) "Last synced ${formatTimestamp(sync.lastSyncAt)}" else "Not synced yet",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    sync.error?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        "Notes syncs when you open or leave the app, a few seconds after you close a note, and every " +
                            "30 minutes. Without a connection, your changes wait on the phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(enabled = !sync.running, onClick = viewModel::syncNow) { Text("Sync now") }
                        if (sync.running) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    OutlinedButton(onClick = { showTokenDialog = true }) { Text("Enter a new token") }
                    TextButton(onClick = {
                        scope.launch { disconnectWarning = runCatching { viewModel.hasUnsyncedChanges() }.getOrDefault(true) }
                    }) { Text("Disconnect this phone") }
                }
            }
        }
    }

    if (showTokenDialog) {
        TokenDialog(
            onSave = { token -> viewModel.replaceToken(token) },
            onDismiss = { showTokenDialog = false },
        )
    }

    disconnectWarning?.let { unsynced ->
        AlertDialog(
            onDismissRequest = { disconnectWarning = null },
            title = { Text("Disconnect this phone?") },
            text = {
                Text(
                    if (unsynced) {
                        "This phone has changes that GitHub does not have yet. Disconnecting deletes them. " +
                            "Sync first if you can."
                    } else {
                        "The phone's copy of the repo and the token are deleted. GitHub and your PC keep everything."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    disconnectWarning = null
                    scope.launch { viewModel.disconnect() }
                }) { Text(if (unsynced) "Delete and disconnect" else "Disconnect") }
            },
            dismissButton = { TextButton(onClick = { disconnectWarning = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TokenDialog(onSave: suspend (String) -> Unit, onDismiss: () -> Unit) {
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New GitHub token") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A fine-grained token for your life repo, with Contents: Read and write.")
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    label = { Text("GitHub token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = token.isNotBlank() && !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            onSave(token)
                            onDismiss()
                        } catch (e: IOException) {
                            error = e.message
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
