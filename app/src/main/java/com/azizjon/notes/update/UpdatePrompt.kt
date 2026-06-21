package com.azizjon.notes.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Checks for a newer release on first composition and, if one exists, shows a dialog
 * to download and install it. No-ops silently when up to date or offline.
 */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val manager = remember { UpdateManager(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var progress by remember { mutableIntStateOf(-1) }
    var dismissed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        info = manager.checkForUpdate()
    }

    val update = info
    if (update == null || dismissed) return

    AlertDialog(
        onDismissRequest = { if (progress < 0) dismissed = true },
        title = { Text("Update available") },
        text = {
            Column {
                Text("Version ${update.versionName} is ready to install.")
                if (update.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(update.notes, style = MaterialTheme.typography.bodySmall)
                }
                if (progress >= 0) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Downloading… $progress%", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = progress < 0,
                onClick = {
                    scope.launch {
                        progress = 0
                        val file = runCatching {
                            manager.downloadApk(update) { progress = it }
                        }.getOrNull()
                        progress = -1
                        if (file != null) manager.installApk(file)
                    }
                },
            ) { Text("Update") }
        },
        dismissButton = {
            if (progress < 0) {
                TextButton(onClick = { dismissed = true }) { Text("Later") }
            }
        },
    )
}
