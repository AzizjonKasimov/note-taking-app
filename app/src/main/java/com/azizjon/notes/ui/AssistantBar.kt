package com.azizjon.notes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azizjon.notes.R
import com.azizjon.notes.assistant.AssistantChoice
import com.azizjon.notes.assistant.AssistantEffort
import com.azizjon.notes.assistant.AssistantModel
import com.azizjon.notes.assistant.AssistantSession
import com.azizjon.notes.assistant.AssistantSession.Status
import com.azizjon.notes.assistant.AssistantSession.UndoState
import com.azizjon.notes.assistant.GatewayClient

/**
 * The "tell me anything" box at the bottom of Today, above the tabs: what the user types or says
 * goes to the assistant, which files it in the repo. The latest exchange shows above the box (the
 * earlier ones on request), with each change the assistant made and Undo. While the keyboard is
 * open, Android slides the screen up to keep the box in sight (the window's adjustPan); padding the
 * box for the keyboard as well would lift it twice.
 */
@Composable
fun AssistantBar(
    session: AssistantSession,
    onOpenFile: (String) -> Unit,
    onOpenSettings: () -> Unit,
    tabs: @Composable () -> Unit,
) {
    val exchanges by session.exchanges.collectAsStateWithLifecycle()
    val choice by session.choice.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var talking by remember { mutableStateOf(false) }
    var showEarlier by rememberSaveable { mutableStateOf(false) }
    var needKey by remember { mutableStateOf(false) }
    val shown = exchanges.filterNot { it.hidden }
    val working = shown.lastOrNull()?.status is Status.Working
    val focus = LocalFocusManager.current

    fun send() {
        if (!session.configured) {
            needKey = true
            return
        }
        needKey = false
        if (session.send(text)) {
            text = ""
            // The keyboard steps aside, so the reply and Today show.
            focus.clearFocus()
        }
    }

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth()) {
            if (needKey) {
                KeyCard(onOpenSettings = onOpenSettings, onClose = { needKey = false })
            }
            if (shown.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (showEarlier) 420.dp else 260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (shown.size > 1) {
                        TextButton(
                            onClick = { showEarlier = !showEarlier },
                            modifier = Modifier.padding(start = 4.dp),
                        ) { Text(if (showEarlier) "Hide earlier" else "Earlier (${shown.size - 1})") }
                    }
                    val visible = if (showEarlier) shown else shown.takeLast(1)
                    visible.forEachIndexed { i, exchange ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        ExchangeCard(
                            exchange = exchange,
                            onStop = session::stop,
                            onClose = { session.dismiss(exchange.id) },
                            onUndo = { index -> session.undo(exchange.id, index) },
                            onRetry = {
                                if (!session.running) {
                                    session.dismiss(exchange.id)
                                    session.send(exchange.message)
                                }
                            },
                            onSaveToInbox = { session.saveToInbox(exchange.id) },
                            onOpenFile = onOpenFile,
                            onOpenSettings = onOpenSettings,
                        )
                    }
                }
            }
            // The model and thinking level, as in the Claude app; picked here and kept between uses.
            TextButton(
                onClick = { picking = true },
                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(choice.label, style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change the model or thinking level")
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Tell me anything") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                IconButton(onClick = { talking = true }) {
                    Icon(painterResource(R.drawable.ic_mic), contentDescription = "Say it")
                }
                IconButton(enabled = text.isNotBlank() && !working, onClick = ::send) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
            tabs()
        }
    }

    if (picking) {
        ChoiceSheet(choice = choice, onChoose = session::choose, onDismiss = { picking = false })
    }

    if (talking) {
        // What is said lands in the box, to be checked before it goes (the user's choice, 2026-11-26).
        VoiceDialog(
            onText = { said -> text = listOf(text.trim(), said.trim()).filter { it.isNotEmpty() }.joinToString(" ") },
            onClose = { talking = false },
        )
    }
}

@Composable
private fun ExchangeCard(
    exchange: AssistantSession.Exchange,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onUndo: (Int) -> Unit,
    onRetry: () -> Unit,
    onSaveToInbox: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val status = exchange.status
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                exchange.message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (status is Status.Working) {
                TextButton(onClick = onStop) { Text("Stop") }
            } else {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Hide") }
            }
        }
        exchange.ranWith?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        when (status) {
            is Status.Working -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    status.progress + "…",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Status.Done -> exchange.reply?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 12.dp))
            }
            Status.Stopped -> Text("Stopped.", style = MaterialTheme.typography.bodyMedium)
            is Status.Failed -> {
                Text(
                    status.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (status.error == GatewayClient.NO_KEY) {
                        TextButton(onClick = onOpenSettings) { Text("Open Settings") }
                    } else {
                        TextButton(onClick = onRetry) { Text("Try again") }
                    }
                    if (exchange.savedTo == null) TextButton(onClick = onSaveToInbox) { Text("Save to inbox") }
                }
            }
        }
        exchange.savedTo?.let { path ->
            Text(
                "Saved as $path, for later.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable { onOpenFile(path) }.padding(vertical = 4.dp),
            )
        }
        exchange.changes.forEachIndexed { index, item ->
            ChangeRow(item, onUndo = { onUndo(index) }, onOpenFile = onOpenFile)
        }
    }
}

@Composable
private fun ChangeRow(item: AssistantSession.ChangeItem, onUndo: () -> Unit, onOpenFile: (String) -> Unit) {
    val path = item.change.paths.firstOrNull().orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Column(Modifier.weight(1f).clickable(enabled = path.isNotEmpty()) { onOpenFile(path) }.padding(vertical = 4.dp)) {
            Text(
                item.change.summary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                textDecoration = if (item.undo == UndoState.UNDONE) TextDecoration.LineThrough else null,
            )
            Text(
                path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (item.undo) {
            UndoState.KEPT -> TextButton(onClick = onUndo) { Text("Undo") }
            UndoState.UNDOING -> CircularProgressIndicator(Modifier.padding(12.dp).size(16.dp), strokeWidth = 2.dp)
            UndoState.UNDONE -> Text("Undone", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(12.dp))
            UndoState.CANNOT -> Text(
                "Changed since:\nopen it to undo",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

/** The models on offer (never Fable) and the thinking levels, kept for the next messages. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ChoiceSheet(choice: AssistantChoice, onChoose: (AssistantChoice) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Model", style = MaterialTheme.typography.titleMedium)
            AssistantModel.entries.forEach { model ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = model == choice.model, role = Role.RadioButton) { onChoose(choice.copy(model = model)) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = model == choice.model, onClick = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(model.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${model.note} · ${model.price} per million tokens",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text("Thinking", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistantEffort.entries.forEach { effort ->
                    FilterChip(
                        selected = effort == choice.effort,
                        onClick = { onChoose(choice.copy(effort = effort)) },
                        label = { Text(effort.label) },
                    )
                }
            }
            Text(
                "Deeper thinking and bigger models take longer and use more of your Claude plan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KeyCard(onOpenSettings: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "To use the assistant, add your AI gateway key in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpenSettings) { Text("Settings") }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Hide") }
    }
}
