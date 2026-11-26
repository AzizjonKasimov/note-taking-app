package com.azizjon.notes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.azizjon.notes.NotesApplication

/**
 * The assistant's key to the user's AI gateway. It is entered here rather than shipped in the app,
 * since the app's releases are public, and kept sealed like the GitHub token.
 */
@Composable
fun AssistantSettingsCard() {
    val keys = (LocalContext.current.applicationContext as NotesApplication).gatewayKey
    var saved by remember { mutableStateOf(keys.hasToken()) }
    var entry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Assistant", style = MaterialTheme.typography.titleMedium)
            Text(
                "The box at the bottom of Today. What you write or say there goes to Claude through your own AI " +
                    "gateway, with the notes the assistant reads to file it. Each change it makes is a commit you " +
                    "can undo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (saved) "Your AI gateway key is saved." else "No AI gateway key yet.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = entry,
                onValueChange = {
                    entry = it.trim()
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (saved) "New AI gateway key" else "AI gateway key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = entry.isNotEmpty(),
                    onClick = {
                        if (entry.length !in MIN_KEY..MAX_KEY) {
                            error = "That is not an AI gateway key: it should be $MIN_KEY to $MAX_KEY characters."
                        } else {
                            keys.save(entry)
                            entry = ""
                            saved = true
                        }
                    },
                ) { Text("Save") }
                if (saved) {
                    TextButton(onClick = {
                        keys.clear()
                        saved = false
                    }) { Text("Remove the key") }
                }
            }
        }
    }
}

private const val MIN_KEY = 16
private const val MAX_KEY = 512
