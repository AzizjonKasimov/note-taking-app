package com.azizjon.notes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azizjon.notes.data.DEFAULT_NOTEBOOK_ID
import com.azizjon.notes.data.DEFAULT_NOTEBOOK_NAME
import com.azizjon.notes.data.Notebook
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichText
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditScreen(
    viewModel: NotesViewModel,
    noteId: Long,
    onDone: () -> Unit,
) {
    val notebooks by viewModel.notebooks.collectAsStateWithLifecycle()
    val selectedNotebookId by viewModel.selectedNotebookId.collectAsStateWithLifecycle()

    var title by rememberSaveable { mutableStateOf("") }
    var notebookId by rememberSaveable { mutableStateOf(DEFAULT_NOTEBOOK_ID) }
    // Existing notes open in a read view (where links are tappable); new notes go straight to editing.
    var editing by rememberSaveable { mutableStateOf(noteId <= 0L) }
    val richTextState = rememberRichTextState()

    val linkColor = MaterialTheme.colorScheme.primary
    LaunchedEffect(Unit) {
        richTextState.config.linkColor = linkColor
        richTextState.config.linkTextDecoration = TextDecoration.Underline
    }

    // Typing "1. " / "- " / "* " at a line start auto-starts a list (Docs/Notion-style).
    MarkdownShortcuts(richTextState)

    // Load the existing note once; a brand new note inherits the notebook currently in view.
    LaunchedEffect(noteId) {
        if (noteId > 0) {
            viewModel.load(noteId)?.let {
                title = it.title
                richTextState.setMarkdown(it.content)
                notebookId = it.notebookId
            }
        } else {
            notebookId = selectedNotebookId ?: DEFAULT_NOTEBOOK_ID
        }
    }

    fun persist() {
        val content = richTextState.toMarkdown()
        if (title.isNotBlank() || content.isNotBlank()) {
            viewModel.save(noteId, title, content, notebookId)
        }
    }

    // Leaving while editing saves; leaving the read view doesn't touch the note (no stale re-saves).
    fun leave() {
        if (editing) persist()
        onDone()
    }

    BackHandler { leave() }

    val notebookName = notebooks.firstOrNull { it.id == notebookId }?.name?.ifBlank { "Untitled" }
        ?: DEFAULT_NOTEBOOK_NAME

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (editing) {
                            if (noteId > 0) "Edit note" else "New note"
                        } else {
                            title.ifBlank { "Untitled" }
                        },
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (noteId > 0) {
                        IconButton(onClick = {
                            viewModel.deleteById(noteId)
                            onDone()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                    if (editing) {
                        IconButton(onClick = {
                            persist()
                            if (noteId > 0) editing = false else onDone()
                        }) {
                            Icon(Icons.Filled.Check, contentDescription = "Done")
                        }
                    } else {
                        IconButton(onClick = { editing = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (editing) {
                RichTextToolbar(state = richTextState, modifier = Modifier.imePadding())
            }
        },
    ) { padding ->
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
                    placeholder = { Text("Title") },
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    singleLine = true,
                    colors = transparentFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                NotebookPicker(
                    notebooks = notebooks,
                    selectedId = notebookId,
                    onSelect = { notebookId = it },
                )
                BasicRichTextEditor(
                    state = richTextState,
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
                Text(
                    text = notebookName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                // Read view: the default platform UriHandler makes links tappable (opens the browser).
                BasicRichText(
                    state = richTextState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

/** Compact dropdown under the title showing — and letting the user change — the note's notebook. */
@Composable
private fun NotebookPicker(
    notebooks: List<Notebook>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val name = notebooks.firstOrNull { it.id == selectedId }?.name?.ifBlank { "Untitled" }
        ?: DEFAULT_NOTEBOOK_NAME
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(name)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose notebook")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            notebooks.forEach { nb ->
                DropdownMenuItem(
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
