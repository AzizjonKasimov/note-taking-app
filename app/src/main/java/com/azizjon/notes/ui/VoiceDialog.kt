package com.azizjon.notes.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azizjon.notes.NotesApplication
import com.azizjon.notes.R
import com.azizjon.notes.voice.ModelState
import com.azizjon.notes.voice.SpeechModel
import com.azizjon.notes.voice.VoiceState
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Asks for the microphone (and, on Android 13+, notifications, so the recording's Stop button shows
 * on the lock screen), then runs [onGranted]. Returns the function a mic button calls.
 */
@Composable
fun rememberMicStarter(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val granted by rememberUpdatedState(onGranted)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            granted()
        } else {
            Toast.makeText(context, "Notes needs the microphone for voice notes.", Toast.LENGTH_LONG).show()
        }
    }
    return remember(launcher) {
        {
            val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (hasMic) {
                granted()
            } else {
                val wanted = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                launcher.launch(wanted.toTypedArray())
            }
        }
    }
}

/**
 * The dictation dialog. It offers the speech model's one-time download when the phone does not
 * have it, offers a recording whose text was never used, then records and turns the speech into
 * text on the phone. [onText] receives the text, after which the dialog closes.
 */
@Composable
fun VoiceDialog(onText: (String) -> Unit, onClose: () -> Unit) {
    val voice = (LocalContext.current.applicationContext as NotesApplication).voice
    val model by voice.models.state.collectAsStateWithLifecycle()
    val state by voice.state.collectAsStateWithLifecycle()
    var leftover by remember { mutableStateOf(voice.leftover()) }
    var started by remember { mutableStateOf(state != VoiceState.Idle) }
    val deliver by rememberUpdatedState(onText)

    // Talking starts as soon as the mic is tapped, once the model is on the phone and nothing is left over.
    LaunchedEffect(model, leftover, started) {
        if (!started && model is ModelState.Ready && leftover == null) {
            started = true
            voice.start()
        }
    }
    LaunchedEffect(state) {
        (state as? VoiceState.Done)?.let {
            deliver(it.text)
            voice.finish()
            onClose()
        }
    }

    BackHandler {
        when (state) {
            is VoiceState.Recording -> voice.stop() // keep what was said
            is VoiceState.Transcribing -> Unit
            else -> onClose()
        }
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val current = leftover
                when {
                    model !is ModelState.Ready -> ModelContent(
                        model = model,
                        onDownload = voice.models::download,
                        onClose = onClose,
                    )
                    current != null && state == VoiceState.Idle -> LeftoverContent(
                        file = current,
                        onUse = {
                            leftover = null
                            started = true
                            voice.retry(current)
                        },
                        onDelete = {
                            voice.discard(current)
                            leftover = voice.leftover()
                        },
                    )
                    else -> DictationContent(
                        state = state,
                        onStop = voice::stop,
                        onCancel = {
                            voice.finish()
                            onClose()
                        },
                        onRetry = { voice.retry() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelContent(model: ModelState, onDownload: () -> Unit, onClose: () -> Unit) {
    Text("Voice notes", style = MaterialTheme.typography.titleLarge)
    when (model) {
        ModelState.Missing, is ModelState.Failed -> {
            Text(
                "Notes turns speech into text on your phone, with no internet. First it needs a one-time " +
                    "download of the speech model (${megabytes(SpeechModel.totalBytes)}). It downloads over Wi-Fi.",
            )
            if (model is ModelState.Failed) Text(model.message, color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("Not now") }
                Button(onClick = onDownload) { Text("Download") }
            }
        }
        is ModelState.Downloading -> {
            Text(
                if (model.waitingForWifi) "Waiting for Wi-Fi to download the speech model."
                else "Downloading the speech model: ${megabytes(model.done)} of ${megabytes(model.total)}.",
            )
            LinearProgressIndicator(
                progress = { if (model.total > 0) model.done.toFloat() / model.total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "You can keep using Notes; the download goes on in the background.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("Hide") }
            }
        }
        ModelState.Checking -> {
            Text("Checking the downloaded model…")
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is ModelState.Ready -> Unit
    }
}

@Composable
private fun LeftoverContent(file: File, onUse: () -> Unit, onDelete: () -> Unit) {
    val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(file.lastModified()))
    Text("Unsaved recording", style = MaterialTheme.typography.titleLarge)
    Text("A recording from $time was never turned into text.")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDelete) { Text("Delete it") }
        Button(onClick = onUse) { Text("Turn it into text") }
    }
}

@Composable
private fun DictationContent(
    state: VoiceState,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        VoiceState.Idle, is VoiceState.Done -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        is VoiceState.Recording -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    painterResource(R.drawable.ic_mic),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp),
                )
                Text(
                    duration(state.millis),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text("Listening. Talk for as long as you like; pauses are fine.")
            LinearProgressIndicator(progress = { state.level }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(onClick = onStop) {
                    Icon(painterResource(R.drawable.ic_stop), contentDescription = null)
                    Text("Stop", Modifier.padding(start = 8.dp))
                }
            }
        }
        is VoiceState.Transcribing -> {
            Text("Turning speech into text…", style = MaterialTheme.typography.titleMedium)
            if (state.total > 0) {
                LinearProgressIndicator(
                    progress = { state.done.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
        is VoiceState.Failed -> {
            Text("Voice note", style = MaterialTheme.typography.titleLarge)
            Text(state.message, color = MaterialTheme.colorScheme.error)
            if (state.canRetry) Text("The recording is kept, so nothing is lost.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text(if (state.canRetry) "Delete recording" else "Close") }
                if (state.canRetry) Button(onClick = onRetry) { Text("Try again") }
            }
        }
    }
}

internal fun megabytes(bytes: Long): String = "%,d MB".format(bytes / 1_000_000)

private fun duration(millis: Long): String {
    val seconds = millis / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
