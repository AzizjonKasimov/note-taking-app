package com.azizjon.notes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azizjon.notes.data.DEFAULT_NOTEBOOK_ID
import com.azizjon.notes.data.DEFAULT_NOTEBOOK_NAME
import com.azizjon.notes.data.EditorSnapshot
import com.azizjon.notes.data.Notebook
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichText
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditScreen(
    viewModel: NotesViewModel,
    noteId: Long,
    onDone: () -> Unit,
) {
    val notebooks by viewModel.notebooks.collectAsStateWithLifecycle()
    val saveStatus by viewModel.editorSaveStatus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var title by rememberSaveable(noteId) { mutableStateOf("") }
    var notebookId by rememberSaveable(noteId) { mutableStateOf(DEFAULT_NOTEBOOK_ID) }
    var editing by rememberSaveable(noteId) { mutableStateOf(false) }
    var startedAsNew by rememberSaveable(noteId) { mutableStateOf(false) }
    var loaded by rememberSaveable(noteId) { mutableStateOf(false) }
    var finishing by rememberSaveable(noteId) { mutableStateOf(false) }
    var showDeleteConfirmation by rememberSaveable(noteId) { mutableStateOf(false) }
    var showSaveFailure by rememberSaveable(noteId) { mutableStateOf(false) }
    var finishLeavesScreen by rememberSaveable(noteId) { mutableStateOf(true) }
    val richTextState = rememberRichTextState()

    fun currentSnapshot() = EditorSnapshot(
        noteId = noteId,
        title = title,
        content = richTextState.toMarkdown(),
        notebookId = notebookId,
    )

    val linkColor = MaterialTheme.colorScheme.primary
    LaunchedEffect(linkColor) {
        richTextState.config.linkColor = linkColor
        richTextState.config.linkTextDecoration = TextDecoration.Underline
    }

    // Typing "1. " / "- " / "* " at a line start auto-starts a list (Docs/Notion-style).
    MarkdownShortcuts(richTextState)

    // Pending in-memory state wins across rotation; a cold process always reloads Room.
    LaunchedEffect(noteId) {
        if (noteId <= 0L) {
            onDone()
            return@LaunchedEffect
        }

        val pending = viewModel.currentEditorSnapshot(noteId)
        val note = if (pending == null) viewModel.load(noteId) else null
        if (pending == null && note == null) {
            onDone()
            return@LaunchedEffect
        }

        val initial = pending ?: EditorSnapshot(
            noteId = noteId,
            title = note!!.title,
            content = note.content,
            notebookId = note.notebookId,
        )
        title = initial.title
        richTextState.setMarkdown(initial.content)
        notebookId = initial.notebookId

        val session = viewModel.activeEditorSession.value?.takeIf { it.noteId == noteId }
        startedAsNew = session?.isNew == true
        if (session != null) {
            editing = viewModel.beginEditing(noteId, session.isNew, initial)
        }
        loaded = true
    }

    // Begin observing only after loading, so the initial empty Compose state cannot touch Room.
    LaunchedEffect(loaded, editing, noteId) {
        if (!loaded || !editing) return@LaunchedEffect
        snapshotFlow { Triple(title, notebookId, richTextState.annotatedString) }
            .distinctUntilChanged()
            .collect {
                viewModel.submitEditorSnapshot(currentSnapshot())
            }
    }

    // Backgrounding keeps the session active but pushes the newest revision to Room immediately.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (loaded && editing) {
            viewModel.submitEditorSnapshot(currentSnapshot())
            viewModel.flushEditorOnStop()
        }
    }

    fun requestFinish(leaveScreen: Boolean) {
        if (!editing) {
            onDone()
            return
        }
        if (finishing) return
        finishLeavesScreen = leaveScreen
        viewModel.submitEditorSnapshot(currentSnapshot())
        finishing = true
        scope.launch {
            if (viewModel.flushAndFinishEditor()) {
                showSaveFailure = false
                if (leaveScreen || startedAsNew) {
                    onDone()
                } else {
                    finishing = false
                    editing = false
                }
            } else {
                finishing = false
                viewModel.submitEditorSnapshot(currentSnapshot())
                showSaveFailure = true
            }
        }
    }

    // Repeated system Back events are consumed while the synchronous finish is in flight.
    BackHandler { requestFinish(leaveScreen = true) }

    val notebookName = notebooks.firstOrNull { it.id == notebookId }?.name?.ifBlank { "Untitled" }
        ?: DEFAULT_NOTEBOOK_NAME

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (editing) {
                            if (startedAsNew) "New note" else "Edit note"
                        } else {
                            title.ifBlank { "Untitled" }
                        },
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(enabled = !finishing, onClick = { requestFinish(leaveScreen = true) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (editing && loaded) SaveStatusIndicator(saveStatus, viewModel::retryEditorSave)
                    if (loaded) {
                        IconButton(
                            enabled = !finishing,
                            onClick = { showDeleteConfirmation = true },
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Move to Trash")
                        }
                    }
                    if (editing) {
                        IconButton(
                            enabled = loaded && !finishing,
                            onClick = { requestFinish(leaveScreen = false) },
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = "Done")
                        }
                    } else if (loaded) {
                        IconButton(
                            onClick = {
                                val began = viewModel.beginEditing(noteId, false, currentSnapshot())
                                if (began) editing = true
                            },
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (editing && loaded) {
                RichTextToolbar(state = richTextState, modifier = Modifier.imePadding())
            }
        },
    ) { padding ->
        if (!loaded) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                if (editing) {
                    TextField(
                        value = title,
                        onValueChange = { title = it },
                        enabled = !finishing,
                        placeholder = { Text("Title") },
                        textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        singleLine = true,
                        colors = transparentFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NotebookPicker(
                        notebooks = notebooks,
                        selectedId = notebookId,
                        enabled = !finishing,
                        onSelect = { notebookId = it },
                    )
                    BasicRichTextEditor(
                        state = richTextState,
                        readOnly = finishing,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 8.dp),
                    )
                } else {
                    if (title.isNotBlank()) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    ) {
                        notebooks.firstOrNull { it.id == notebookId }?.let { NotebookMarker(it, 24.dp) }
                        Text(
                            text = notebookName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    // Read view: the default platform UriHandler makes links open in the browser.
                    BasicRichText(
                        state = richTextState,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Move note to Trash?") },
            text = { Text("You can restore it later from Trash.") },
            confirmButton = {
                TextButton(
                    enabled = !finishing,
                    onClick = {
                        showDeleteConfirmation = false
                        val wasEditing = editing
                        finishing = true
                        scope.launch {
                            runCatching { viewModel.moveToTrashAndFinishEditor(noteId) }
                                .onSuccess { onDone() }
                                .onFailure {
                                    if (wasEditing) {
                                        viewModel.beginEditing(noteId, startedAsNew, currentSnapshot())
                                    }
                                    finishing = false
                                    showDeleteConfirmation = true
                                }
                        }
                    },
                ) {
                    Text("Move to Trash")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showSaveFailure) {
        AlertDialog(
            onDismissRequest = { showSaveFailure = false },
            title = { Text("Couldn’t save") },
            text = {
                Text("Your latest changes are still in the editor. Retry, or explicitly discard them and leave the editor.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSaveFailure = false
                        requestFinish(finishLeavesScreen)
                    },
                ) {
                    Text("Retry")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSaveFailure = false
                        finishing = true
                        scope.launch {
                            viewModel.discardEditorChangesAndFinish()
                            if (finishLeavesScreen || startedAsNew) {
                                onDone()
                            } else {
                                finishing = false
                                editing = false
                            }
                        }
                    },
                ) {
                    Text("Discard and leave")
                }
            },
        )
    }
}

@Composable
private fun SaveStatusIndicator(status: EditorSaveStatus, onRetry: () -> Unit) {
    when (status) {
        EditorSaveStatus.SAVING -> Text(
            text = "Saving…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = "Note is saving" },
        )
        EditorSaveStatus.SAVED -> Text(
            text = "Saved",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = "Note saved" },
        )
        EditorSaveStatus.ERROR -> TextButton(onClick = onRetry) {
            Text(
                text = "Couldn’t save",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics {
                    contentDescription = "Could not save note. Retry"
                },
            )
        }
    }
}

/** Compact dropdown under the title showing — and letting the user change — the note's notebook. */
@Composable
private fun NotebookPicker(
    notebooks: List<Notebook>,
    selectedId: Long,
    enabled: Boolean,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val name = notebooks.firstOrNull { it.id == selectedId }?.name?.ifBlank { "Untitled" }
        ?: DEFAULT_NOTEBOOK_NAME
    val selected = notebooks.firstOrNull { it.id == selectedId }
    Box {
        TextButton(enabled = enabled, onClick = { expanded = true }) {
            selected?.let {
                NotebookMarker(it, 24.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(name)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose notebook")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            notebooks.forEach { nb ->
                DropdownMenuItem(
                    leadingIcon = { NotebookMarker(nb, 24.dp) },
                    text = { Text(nb.name.ifBlank { "Untitled" }) },
                    onClick = {
                        onSelect(nb.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun transparentFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)
