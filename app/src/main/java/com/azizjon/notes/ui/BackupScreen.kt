package com.azizjon.notes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    var owner by remember { mutableStateOf(state.config.owner) }
    var repo by remember { mutableStateOf(state.config.repo) }
    var branch by remember { mutableStateOf(state.config.branch) }
    var path by remember { mutableStateOf(state.config.path) }
    var token by remember { mutableStateOf(state.config.token) }
    var autoBackup by remember { mutableStateOf(state.config.autoBackup) }

    LaunchedEffect(state.config) {
        owner = state.config.owner
        repo = state.config.repo
        branch = state.config.branch
        path = state.config.path
        token = state.config.token
        autoBackup = state.config.autoBackup
    }

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
            if (state.inProgress) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

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
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("GitHub SQL backup", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${state.config.owner}/${state.config.repo} -> ${state.config.path}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.lastBackupAt > 0) {
                        Text(
                            "Last backup: ${formatBackupTime(state.lastBackupAt)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }

                    OutlinedTextField(
                        value = owner,
                        onValueChange = { owner = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Owner") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = repo,
                        onValueChange = { repo = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Repository") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = branch,
                        onValueChange = { branch = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Branch") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("SQL path") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("GitHub token") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = autoBackup,
                            onCheckedChange = { autoBackup = it },
                            enabled = !state.inProgress,
                        )
                        Text("Auto backup")
                    }
                }
            }

            Button(
                onClick = {
                    viewModel.saveBackupSettings(
                        state.config.copy(
                            owner = owner,
                            repo = repo,
                            branch = branch,
                            path = path,
                            token = token,
                            autoBackup = autoBackup,
                        ),
                    )
                },
                enabled = !state.inProgress,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save GitHub settings") }

            Button(
                onClick = { viewModel.backupNow() },
                enabled = !state.inProgress && state.configured,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Back up now") }

            OutlinedButton(
                onClick = { viewModel.restore() },
                enabled = !state.inProgress && state.configured,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Restore from GitHub") }
        }
    }
}

private fun formatBackupTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))
