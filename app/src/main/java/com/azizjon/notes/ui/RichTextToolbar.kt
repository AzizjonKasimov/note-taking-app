package com.azizjon.notes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState

/**
 * Formatting toolbar bound to a [RichTextState]: bold, italic, bulleted/numbered lists, link, and a
 * "Paste MD" action that parses pasted text (lists, links, **bold**) into formatted content — the
 * migration path, since pasting plain text into a WYSIWYG editor would otherwise stay literal.
 */
@Composable
fun RichTextToolbar(
    state: RichTextState,
    modifier: Modifier = Modifier,
) {
    var showLink by remember { mutableStateOf(false) }
    var showMarkdown by remember { mutableStateOf(false) }

    Surface(modifier = modifier, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton("B", active = state.currentSpanStyle.fontWeight == FontWeight.Bold, bold = true) {
                state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
            }
            ToolButton("I", active = state.currentSpanStyle.fontStyle == FontStyle.Italic, italic = true) {
                state.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
            }
            ToolButton("•", active = state.isUnorderedList) { state.toggleUnorderedList() }
            ToolButton("1.", active = state.isOrderedList) { state.toggleOrderedList() }
            ToolButton(
                label = "⇥",
                contentDescription = "Increase list indent",
                enabled = state.canIncreaseListLevel,
            ) { state.increaseListLevel() }
            ToolButton(
                label = "⇤",
                contentDescription = "Decrease list indent",
                enabled = state.canDecreaseListLevel,
            ) { state.decreaseListLevel() }
            ToolButton("Link", active = state.isLink) { showLink = true }
            ToolButton("Paste MD") { showMarkdown = true }
        }
    }

    if (showLink) {
        LinkDialog(
            initialUrl = state.selectedLinkUrl.orEmpty(),
            onConfirm = { text, url ->
                if (text.isBlank()) state.addLinkToSelection(url) else state.addLink(text, url)
                showLink = false
            },
            onDismiss = { showLink = false },
        )
    }
    if (showMarkdown) {
        MarkdownDialog(
            onConfirm = { raw ->
                val existing = state.toMarkdown()
                val incoming = prepareMarkdownForPaste(raw)
                state.setMarkdown(if (existing.isBlank()) incoming else "$existing\n\n$incoming")
                showMarkdown = false
            },
            onDismiss = { showMarkdown = false },
        )
    }
}

@Composable
private fun ToolButton(
    label: String,
    active: Boolean = false,
    bold: Boolean = false,
    italic: Boolean = false,
    contentDescription: String = label,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(
                if (active && enabled) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .sizeIn(minWidth = 44.dp, minHeight = 40.dp)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            color = if (!enabled) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            } else if (active) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun LinkDialog(
    initialUrl: String,
    onConfirm: (text: String, url: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add link") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text (leave empty to link selection)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim(), normalizeUrl(url.trim())) },
                enabled = url.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MarkdownDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var raw by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert Markdown") },
        text = {
            Column {
                Text(
                    "Paste text from your other app — numbered/bulleted lists, links and " +
                        "**bold** become formatted and added to the note.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    label = { Text("Paste here") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(raw) }, enabled = raw.isNotBlank()) { Text("Insert") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun normalizeUrl(url: String): String =
    if (url.isBlank() || url.contains("://")) url else "https://$url"
