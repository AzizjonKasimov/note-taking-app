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

/**
 * Drawer contents: an "All notes" entry, every top-level folder of the repo with its note count
 * (and an Appearance option), a "New notebook" action that makes a folder, and Trash.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookDrawerSheet(
    folders: List<FolderItem>,
    totalCount: Int,
    selectedFolder: String?,
    onSelect: (String?) -> Unit,
    onCreate: (String) -> Unit,
    onAppearance: (FolderItem) -> Unit,
    trashCount: Int,
    onOpenTrash: () -> Unit,
) {
    var showCreate by rememberSaveable { mutableStateOf(false) }

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
                count = totalCount,
                selected = selectedFolder == null,
                leading = { AllNotesMarker(32.dp) },
                onClick = { onSelect(null) },
            )

            folders.forEach { folder ->
                DrawerRow(
                    label = folder.notebook.name.ifBlank { folder.path },
                    count = folder.count,
                    selected = selectedFolder == folder.path,
                    leading = { NotebookMarker(folder.notebook, 32.dp) },
                    onClick = { onSelect(folder.path) },
                    trailing = { NotebookOverflow(onAppearance = { onAppearance(folder) }) },
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
private fun NotebookOverflow(onAppearance: () -> Unit) {
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
