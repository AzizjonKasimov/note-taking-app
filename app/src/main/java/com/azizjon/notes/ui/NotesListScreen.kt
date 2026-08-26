package com.azizjon.notes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azizjon.notes.data.Note
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(
    viewModel: NotesViewModel,
    onAddNote: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val notebooks by viewModel.notebooks.collectAsStateWithLifecycle()
    val counts by viewModel.notebookCounts.collectAsStateWithLifecycle()
    val selectedNotebookId by viewModel.selectedNotebookId.collectAsStateWithLifecycle()
    val deletedNotes by viewModel.deletedNotes.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var appearanceTargetId by rememberSaveable { mutableStateOf<Long?>(null) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Top-bar title reflects the active notebook, or "All notes" for the unfiltered view.
    val selectedNotebook = selectedNotebookId?.let { id -> notebooks.firstOrNull { it.id == id } }
    val title = selectedNotebook?.name?.ifBlank { "Untitled" } ?: "All notes"
    val notebooksById = notebooks.associateBy { it.id }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !searching,
        drawerContent = {
            NotebookDrawerSheet(
                notebooks = notebooks,
                counts = counts,
                selectedNotebookId = selectedNotebookId,
                onSelect = { id ->
                    viewModel.selectNotebook(id)
                    scope.launch { drawerState.close() }
                },
                onCreate = viewModel::createNotebook,
                onRename = viewModel::renameNotebook,
                onDelete = viewModel::deleteNotebook,
                onAppearance = { notebook ->
                    scope.launch {
                        drawerState.close()
                        appearanceTargetId = notebook.id
                    }
                },
                trashCount = deletedNotes.size,
                onOpenTrash = {
                    scope.launch {
                        drawerState.close()
                        onOpenTrash()
                    }
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                if (searching) {
                    SearchField(
                        query = query,
                        onQueryChange = viewModel::onQueryChange,
                        onClose = {
                            searching = false
                            viewModel.onQueryChange("")
                        },
                    )
                } else {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selectedNotebook != null) {
                                    NotebookMarker(selectedNotebook, 28.dp)
                                } else {
                                    AllNotesMarker(28.dp)
                                }
                                Text(title, modifier = Modifier.padding(start = 10.dp))
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Notebooks")
                            }
                        },
                        actions = {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Filled.Search, contentDescription = "Search")
                            }
                            IconButton(onClick = onOpenBackup) {
                                Icon(Icons.Filled.Settings, contentDescription = "Settings")
                            }
                        },
                    )
                }
            },
            floatingActionButton = {
                FloatingActionButton(onClick = onAddNote) {
                    Icon(Icons.Filled.Add, contentDescription = "New note")
                }
            },
        ) { padding ->
            if (notes.isEmpty()) {
                EmptyState(
                    searching = query.isNotBlank(),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(notes, key = { it.id }) { note ->
                        NoteRow(
                            note = note,
                            notebook = notebooksById[note.notebookId],
                            onClick = { onOpenNote(note.id) },
                        )
                    }
                }
            }
        }
    }

    val appearanceTarget = appearanceTargetId?.let { id -> notebooks.firstOrNull { it.id == id } }
    if (appearanceTarget != null) {
        NotebookAppearanceSheet(
            notebook = appearanceTarget,
            viewModel = viewModel,
            onDismiss = { appearanceTargetId = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search notes") },
                singleLine = true,
                colors = transparentFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteRow(
    note: Note,
    notebook: com.azizjon.notes.data.Notebook?,
    onClick: () -> Unit,
) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = note.title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (note.content.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = contentPreview(note.content),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (notebook != null) {
                    NotebookMarker(notebook, 24.dp)
                    Text(
                        text = notebook.name.ifBlank { "Untitled" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp, end = 8.dp),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Text(
                    text = formatTimestamp(note.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(searching: Boolean, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text = if (searching) {
                "No matching notes"
            } else {
                "No notes yet.\nTap + to create your first note."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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

internal fun formatTimestamp(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))

private val MD_LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
private val MD_LINE_PREFIX = Regex("""(?m)^\s{0,3}(#{1,6}\s+|>\s+|[-*+]\s+|\d+\.\s+)""")
private val MD_EMPHASIS = Regex("""[*_`~]""")

/** Best-effort strip of markdown syntax so the 2-line list preview reads as plain text. */
internal fun contentPreview(md: String): String =
    MD_EMPHASIS.replace(MD_LINE_PREFIX.replace(MD_LINK.replace(md) { it.groupValues[1] }, ""), "")
        .trim()
