package com.azizjon.notes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azizjon.notes.data.DEFAULT_NOTEBOOK_ID
import com.azizjon.notes.data.DEFAULT_NOTEBOOK_NAME
import com.azizjon.notes.data.Notebook

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
    var content by rememberSaveable { mutableStateOf("") }
    var notebookId by rememberSaveable { mutableStateOf(DEFAULT_NOTEBOOK_ID) }

    // Load the existing note once; a brand new note inherits the notebook currently in view.
    LaunchedEffect(noteId) {
        if (noteId > 0) {
            viewModel.load(noteId)?.let {
                title = it.title
                content = it.content
                notebookId = it.notebookId
            }
        } else {
            notebookId = selectedNotebookId ?: DEFAULT_NOTEBOOK_ID
        }
    }

    fun saveAndExit() {
        if (title.isNotBlank() || content.isNotBlank()) {
            viewModel.save(noteId, title, content, notebookId)
        }
        onDone()
    }

    BackHandler { saveAndExit() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (noteId > 0) "Edit note" else "New note") },
                navigationIcon = {
                    IconButton(onClick = { saveAndExit() }) {
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
                    IconButton(onClick = { saveAndExit() }) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
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
            TextField(
                value = content,
                onValueChange = { content = it },
                placeholder = { Text("Start writing…") },
                colors = transparentFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
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
