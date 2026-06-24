package com.azizjon.notes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azizjon.notes.ui.theme.AppThemeMode
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    viewModel: NotesViewModel,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.backupState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Creates / replaces the backup file (user picks a Google Drive location once).
    val chooseTarget = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) viewModel.onBackupTargetChosen(uri) }

    // Restore from any backup file the user picks.
    val pickRestore = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.restoreFromUri(uri) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

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
        snackbarHost = { SnackbarHost(snackbar) },
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
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        AppThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = { onThemeModeChange(mode) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = AppThemeMode.entries.size,
                                ),
                            ) {
                                Text(mode.label)
                            }
                        }
                    }
                }
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Google Drive backup", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (state.configured) {
                            "Your notes are saved to a file in your Google Drive, and backed up automatically a few seconds after each change."
                        } else {
                            "Pick a file in your Google Drive to back your notes up to. No account or setup needed — choose the spot once and the app keeps it updated."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.configured && state.lastBackupAt > 0) {
                        Text(
                            "Last backup: ${formatBackupTime(state.lastBackupAt)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            if (!state.configured) {
                Button(
                    onClick = { chooseTarget.launch("notes-backup.json") },
                    enabled = !state.inProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Set up Drive backup") }
            } else {
                Button(
                    onClick = { viewModel.backupNow() },
                    enabled = !state.inProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Back up now") }

                OutlinedButton(
                    onClick = { viewModel.restore() },
                    enabled = !state.inProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restore from backup") }

                OutlinedButton(
                    onClick = { chooseTarget.launch("notes-backup.json") },
                    enabled = !state.inProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Change backup file") }
            }

            OutlinedButton(
                onClick = { pickRestore.launch(arrayOf("application/json")) },
                enabled = !state.inProgress,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Restore from another file…") }
        }
    }
}

private fun formatBackupTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))
