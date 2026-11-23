package com.azizjon.notes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.azizjon.notes.life.Connection
import kotlinx.coroutines.launch
import java.io.IOException

/** First run: the user makes a GitHub token for the repo and pastes it; the app clones the repo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(viewModel: NotesViewModel, connection: Connection) {
    // The token is kept only in memory here, never in saved screen state.
    var token by remember { mutableStateOf("") }
    var repo by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val cloning = connection as? Connection.Cloning

    Scaffold(topBar = { TopAppBar(title = { Text("Connect your notes") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Notes keeps your notes in your life repo on GitHub, so this phone and your PC have the same notes.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text("1. Make a token on GitHub", style = MaterialTheme.typography.titleMedium)
            Text(
                "Generate a new fine-grained token. Repository access: \"Only select repositories\", " +
                    "and pick your life repo. Permissions: Contents, \"Read and write\". Expiration: 1 year.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { uriHandler.openUri(NEW_TOKEN_PAGE) }) {
                Text("Open GitHub's token page")
            }
            Text("2. Paste it here", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = token,
                onValueChange = { token = it.trim() },
                label = { Text("GitHub token") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = cloning == null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = repo,
                onValueChange = { repo = it.trim() },
                label = { Text("Repo, only if the token opens more than one") },
                placeholder = { Text("owner/life") },
                singleLine = true,
                enabled = cloning == null,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = token.isNotBlank() && cloning == null,
                onClick = {
                    error = null
                    scope.launch {
                        try {
                            viewModel.connect(token, repo.ifBlank { null })
                            token = ""
                        } catch (e: IOException) {
                            error = e.message
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect") }

            if (cloning != null) {
                val percent = cloning.percent
                if (percent != null) {
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text(
                    text = cloning.step + (percent?.let { " $it%" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "The first download is the repo's whole history, about 65 MB. Keep the app open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "The token is stored on this phone, encrypted with a key that never leaves it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val NEW_TOKEN_PAGE = "https://github.com/settings/personal-access-tokens/new"
