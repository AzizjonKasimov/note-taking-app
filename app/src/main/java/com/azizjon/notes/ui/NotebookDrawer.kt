package com.azizjon.notes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azizjon.notes.data.DEFAULT_NOTEBOOK_ID
import com.azizjon.notes.data.DEFAULT_NOTEBOOK_NAME
import com.azizjon.notes.data.Notebook

/**
 * Drawer contents: an "All notes" entry, every notebook with its note count (and a Rename/Delete
 * overflow, except the protected default), and a "New notebook" action. Owns the small
 * create / rename / delete dialogs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookDrawerSheet(
    notebooks: List<Notebook>,
    counts: Map<Long, Int>,
    selectedNotebookId: Long?,
    onSelect: (Long?) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onAppearance: (Notebook) -> Unit,
    trashCount: Int,
    onOpenTrash: () -> Unit,
) {
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Notebook?>(null) }
    var deleteTarget by remember { mutableStateOf<Notebook?>(null) }

    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = "Notebooks",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 8.dp),
            )

            DrawerRow(
                label = "All notes",
                count = counts.values.sum(),
                selected = selectedNotebookId == null,
                leading = { AllNotesMarker(32.dp) },
                onClick = { onSelect(null) },
            )

            notebooks.forEach { nb ->
                DrawerRow(
                    label = nb.name.ifBlank { "Untitled" },
                    count = counts[nb.id] ?: 0,
                    selected = selectedNotebookId == nb.id,
                    leading = { NotebookMarker(nb, 32.dp) },
                    onClick = { onSelect(nb.id) },
                    trailing = {
                        NotebookOverflow(
                            canRenameOrDelete = nb.id != DEFAULT_NOTEBOOK_ID,
                            onAppearance = { onAppearance(nb) },
                            onRename = { renameTarget = nb },
                            onDelete = { deleteTarget = nb },
                        )
                    },
                )
            }

            DrawerRow(
                label = "New notebook",
                count = null,
                selected = false,
                leading = {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { showCreate = true },
            )

            DrawerRow(
                label = "Trash",
                count = trashCount,
                selected = false,
                leading = {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = onOpenTrash,
            )

            Spacer(Modifier.height(12.dp))
        }
    }

    if (showCreate) {
        NotebookNameDialog(
            title = "New notebook",
            initialName = "",
            confirmLabel = "Create",
            onConfirm = { onCreate(it); showCreate = false },
            onDismiss = { showCreate = false },
        )
    }
    renameTarget?.let { nb ->
        NotebookNameDialog(
            title = "Rename notebook",
            initialName = nb.name,
            confirmLabel = "Save",
            onConfirm = { onRename(nb.id, it); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { nb ->
        DeleteNotebookDialog(
            name = nb.name.ifBlank { "Untitled" },
            noteCount = counts[nb.id] ?: 0,
            onConfirm = { onDelete(nb.id); deleteTarget = null },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun DrawerRow(
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(start = 16.dp, end = 4.dp),
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
            )
            if (count != null) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            if (trailing != null) trailing() else Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun NotebookOverflow(
    canRenameOrDelete: Boolean,
    onAppearance: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Notebook options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Appearance") }, onClick = {
                expanded = false
                onAppearance()
            })
            if (canRenameOrDelete) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = { expanded = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = { expanded = false; onDelete() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotebookNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Notebook name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteNotebookDialog(
    name: String,
    noteCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete notebook") },
        text = {
            Text(
                if (noteCount > 0) {
                    val noun = if (noteCount == 1) "note" else "notes"
                    "Delete \"$name\"? Its $noteCount $noun will move to $DEFAULT_NOTEBOOK_NAME."
                } else {
                    "Delete \"$name\"?"
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
