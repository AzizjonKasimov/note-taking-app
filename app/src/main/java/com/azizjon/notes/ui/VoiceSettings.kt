package com.azizjon.notes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azizjon.notes.NotesApplication
import com.azizjon.notes.voice.ModelState
import com.azizjon.notes.voice.SpeechModel

/** Settings for voice notes: the speech model on the phone, and names it should spell right. */
@Composable
fun VoiceSettingsCard() {
    val voice = (LocalContext.current.applicationContext as NotesApplication).voice
    val model by voice.models.state.collectAsStateWithLifecycle()
    var names by remember { mutableStateOf(voice.namesText) }
    var confirmDelete by remember { mutableStateOf(false) }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Voice notes", style = MaterialTheme.typography.titleMedium)
            when (val m = model) {
                ModelState.Missing, is ModelState.Failed -> {
                    Text(
                        "Speech becomes text on the phone, with no internet, after a one-time download of the " +
                            "speech model (${megabytes(SpeechModel.totalBytes)}, over Wi-Fi).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (m is ModelState.Failed) Text(m.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = voice.models::download) { Text("Download the speech model") }
                }
                is ModelState.Downloading -> {
                    Text(
                        if (m.waitingForWifi) "Waiting for Wi-Fi."
                        else "Downloading: ${megabytes(m.done)} of ${megabytes(m.total)}.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { if (m.total > 0) m.done.toFloat() / m.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = voice.models::delete) { Text("Cancel the download") }
                }
                ModelState.Checking -> {
                    Text("Checking the downloaded model…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is ModelState.Ready -> {
                    Text(
                        "The speech model is on this phone (${megabytes(SpeechModel.totalBytes)}): NVIDIA " +
                            "Parakeet TDT 0.6B v2, English.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete the speech model") }
                }
            }
            OutlinedTextField(
                value = names,
                onValueChange = {
                    names = it
                    voice.namesText = it
                },
                label = { Text("Names to spell right") },
                supportingText = { Text("One per line: people, places, brands.") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete the speech model?") },
            text = { Text("Voice notes stop working until it is downloaded again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    voice.models.delete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
