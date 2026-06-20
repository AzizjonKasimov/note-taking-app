package com.azizjon.notes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditScreen(
    viewModel: NotesViewModel,
    noteId: Long,
    onDone: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }

    // Load the existing note once (no-op for a brand new note where noteId <= 0).
    LaunchedEffect(noteId) {
        if (noteId > 0) {
            viewModel.load(noteId)?.let {
                title = it.title
                content = it.content
            }
        }
    }

    fun saveAndExit() {
        if (title.isNotBlank() || content.isNotBlank()) {
            viewModel.save(noteId, title, content)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun transparentFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)
