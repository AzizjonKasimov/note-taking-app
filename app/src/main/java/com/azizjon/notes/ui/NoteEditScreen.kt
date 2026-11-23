package com.azizjon.notes.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azizjon.notes.R
import com.azizjon.notes.data.EditorSnapshot
import com.azizjon.notes.life.NEW_NOTE_PREFIX
import com.azizjon.notes.life.TextMerge
import com.azizjon.notes.life.TitledText
import com.azizjon.notes.life.joinTitle
import com.azizjon.notes.life.noteTitle
import com.azizjon.notes.life.splitTitle
import com.azizjon.notes.voice.placeDictation
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichText
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * One note of the repo: a read view, and an editor for its Markdown.
 *
 * The editor works on the file's text as it is (a title field for a first-line `# ` heading, and
 * the rest). Rich editing is offered only for notes whose Markdown comes back unchanged from the
 * rich editor, since it would rewrite tables, front matter, and `<!-- -->` notes in others.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditScreen(
    viewModel: NotesViewModel,
    path: String,
    isNew: Boolean,
    onDone: () -> Unit,
    startVoice: Boolean = false,
) {
    val saveStatus by viewModel.editorSaveStatus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val prefs = LocalContext.current.getSharedPreferences(EDITOR_PREFS, Context.MODE_PRIVATE)

    var loaded by rememberSaveable(path) { mutableStateOf(false) }
    var editing by rememberSaveable(path) { mutableStateOf(false) }
    var finishing by rememberSaveable(path) { mutableStateOf(false) }
    var showDeleteConfirmation by rememberSaveable(path) { mutableStateOf(false) }
    var showSaveFailure by rememberSaveable(path) { mutableStateOf(false) }
    var finishLeavesScreen by rememberSaveable(path) { mutableStateOf(true) }
    var voiceOpen by rememberSaveable(path) { mutableStateOf(false) }
    var voiceStartedOnOpen by rememberSaveable(path) { mutableStateOf(false) }

    // The note in parts: a title field when the first line is a "# " heading, and the rest.
    var titled by remember(path) { mutableStateOf(false) }
    var title by remember(path) { mutableStateOf("") }
    var prefix by remember(path) { mutableStateOf("") }
    var body by remember(path) { mutableStateOf(TextFieldValue("")) }
    var bodyTrailing by remember(path) { mutableStateOf("") }
    var richSafe by remember(path) { mutableStateOf(false) }
    var rich by remember(path) { mutableStateOf(false) }
    // Dictation goes where the writer tapped; until then it is added at the end as a new paragraph.
    var cursorPlaced by remember(path) { mutableStateOf(false) }
    val richState = rememberRichTextState()
    val readState = rememberRichTextState()

    val linkColor = MaterialTheme.colorScheme.primary
    LaunchedEffect(linkColor) {
        for (state in listOf(richState, readState)) {
            state.config.linkColor = linkColor
            state.config.linkTextDecoration = TextDecoration.Underline
        }
    }
    // Typing "1. " / "- " / "* " at a line start starts a list in rich mode (Docs/Notion-style).
    MarkdownShortcuts(richState)

    fun bodyText(): String = if (rich) richState.toMarkdown().trimEnd() + bodyTrailing else body.text
    fun currentText(): String = if (titled) joinTitle(title, prefix, bodyText()) else bodyText()
    fun currentSnapshot() = EditorSnapshot(path, currentText())

    /** Puts [text] into the fields, keeping the cursor about where it was. */
    fun show(text: String) {
        val split: TitledText? = splitTitle(text) ?: if (text.isEmpty()) TitledText("", NEW_NOTE_PREFIX, "") else null
        titled = split != null
        title = split?.title.orEmpty()
        prefix = split?.prefix.orEmpty()
        val newBody = split?.body ?: text
        val cursor = body.selection.start.coerceAtMost(newBody.length)
        body = TextFieldValue(newBody, TextRange(cursor))
        bodyTrailing = newBody.substring(newBody.trimEnd().length)
        richSafe = isRichSafe(newBody)
        if (!richSafe) rich = false
        if (rich) richState.setMarkdown(newBody)
        readState.setMarkdown(newBody)
    }

    fun setRich(on: Boolean) {
        if (on == rich) return
        if (on) {
            val text = body.text
            if (!isRichSafe(text)) return
            bodyTrailing = text.substring(text.trimEnd().length)
            richState.setMarkdown(text)
            rich = true
        } else {
            body = TextFieldValue(richState.toMarkdown().trimEnd() + bodyTrailing)
            rich = false
        }
        prefs.edit().putBoolean(KEY_RICH, on).apply()
    }

    // Pending in-memory text wins across rotation; otherwise the file is read again.
    LaunchedEffect(path) {
        val pending = viewModel.currentEditorSnapshot(path)
        val text = pending?.content ?: viewModel.load(path) ?: if (isNew) "" else null
        if (text == null) {
            onDone()
            return@LaunchedEffect
        }
        show(text)
        if (prefs.getBoolean(KEY_RICH, false) && richSafe) setRich(true)
        val session = viewModel.activeEditorSession.value?.takeIf { it.path == path }
        if (session != null) editing = viewModel.beginEditing(path, session.isNew, EditorSnapshot(path, text))
        loaded = true
    }

    // Save every change, but only once the note has loaded.
    LaunchedEffect(loaded, editing, path, rich) {
        if (!loaded || !editing) return@LaunchedEffect
        snapshotFlow { Triple(title, body.text, if (rich) richState.annotatedString else null) }
            .distinctUntilChanged()
            .collect { viewModel.submitEditorSnapshot(currentSnapshot()) }
    }

    // A sync or a save that merged changed the note under the editor: show the new text, unless
    // the writer has typed since (the next save merges again).
    LaunchedEffect(path) {
        viewModel.editorTakeIn.collect { takeIn ->
            if (currentText() == takeIn.forText) {
                show(takeIn.diskText)
                viewModel.tookIn(path, takeIn.diskText)
            }
        }
    }
    // In the read view, a sync's changes are shown at once.
    LaunchedEffect(path) {
        viewModel.changedPaths.collect { paths ->
            if (!editing && loaded && path in paths) viewModel.load(path)?.let { show(it) }
        }
    }

    // Backgrounding keeps the session but pushes the newest text to the file at once.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (loaded && editing) {
            viewModel.submitEditorSnapshot(currentSnapshot())
            viewModel.flushEditorOnStop()
        }
    }

    fun startEditing(): Boolean {
        if (!editing) editing = viewModel.beginEditing(path, isNew, currentSnapshot())
        return editing
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
            if (viewModel.flushAndFinishEditor(title.takeIf { titled })) {
                showSaveFailure = false
                if (leaveScreen || isNew) {
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

    // Repeated system Back events are consumed while the finish is in flight.
    BackHandler { requestFinish(leaveScreen = true) }

    val startVoiceNote = rememberMicStarter { if (startEditing()) voiceOpen = true }

    // A note opened with the list's mic button starts listening right away.
    LaunchedEffect(loaded) {
        if (loaded && startVoice && !voiceStartedOnOpen) {
            voiceStartedOnOpen = true
            startVoiceNote()
        }
    }

    val displayTitle = if (titled && title.isNotBlank()) title else noteTitle(path, currentText().take(4_000))

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when {
                            !editing -> displayTitle
                            isNew -> "New note"
                            else -> "Edit note"
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
                        IconButton(enabled = !finishing, onClick = startVoiceNote) {
                            Icon(painterResource(R.drawable.ic_mic), contentDescription = "Voice note")
                        }
                        if (!isNew) {
                            IconButton(enabled = !finishing, onClick = { showDeleteConfirmation = true }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete note")
                            }
                        }
                    }
                    if (editing) {
                        IconButton(enabled = loaded && !finishing, onClick = { requestFinish(leaveScreen = false) }) {
                            Icon(Icons.Filled.Check, contentDescription = "Done")
                        }
                    } else if (loaded) {
                        IconButton(onClick = { startEditing() }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (editing && loaded && rich) {
                RichTextToolbar(state = richState, modifier = Modifier.imePadding())
            }
        },
    ) { padding ->
        if (!loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            if (TextMerge.hasConflict(currentText())) {
                ConflictCard(
                    enabled = !finishing,
                    onKeep = { keep ->
                        TextMerge.resolve(currentText(), keep)?.let { resolved ->
                            if (startEditing()) {
                                val wasRich = rich
                                show(resolved)
                                if (wasRich && richSafe) setRich(true)
                            }
                        }
                    },
                )
            }
            if (editing) {
                EditorHeader(
                    path = path,
                    rich = rich,
                    richSafe = richSafe,
                    enabled = !finishing,
                    onRichChange = ::setRich,
                )
                if (titled) {
                    BasicTextField(
                        value = title,
                        onValueChange = { title = it.replace("\n", " ") },
                        readOnly = finishing,
                        textStyle = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .semantics { contentDescription = "Title" },
                        decorationBox = { inner ->
                            Box {
                                if (title.isEmpty()) {
                                    Text(
                                        "Title",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
                val bodyModifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp)
                    .onFocusChanged { if (it.isFocused) cursorPlaced = true }
                if (rich) {
                    BasicRichTextEditor(
                        state = richState,
                        readOnly = finishing,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        modifier = bodyModifier,
                    )
                } else {
                    BasicTextField(
                        value = body,
                        onValueChange = { body = it },
                        readOnly = finishing,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = bodyModifier
                            .verticalScroll(rememberScrollState())
                            .semantics { contentDescription = "Note text" },
                        decorationBox = { inner ->
                            Box {
                                if (body.text.isEmpty()) {
                                    Text(
                                        "Write in Markdown",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
            } else {
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
                if (titled && title.isNotBlank()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                // Read view: the default platform UriHandler makes links open in the browser.
                BasicRichText(
                    state = readState,
                    style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }

    if (voiceOpen) {
        VoiceDialog(
            onText = { text ->
                if (rich) {
                    val selection = richState.selection
                    if (cursorPlaced && !selection.collapsed) {
                        richState.replaceSelectedText(text.trim())
                    } else {
                        val place = placeDictation(
                            noteText = richState.annotatedString.text,
                            cursor = if (cursorPlaced) selection.start else null,
                            dictated = text,
                        )
                        richState.addTextAtIndex(place.index, place.text)
                    }
                } else {
                    val selection = body.selection
                    body = if (cursorPlaced && !selection.collapsed) {
                        val words = text.trim()
                        TextFieldValue(
                            body.text.replaceRange(selection.min, selection.max, words),
                            TextRange(selection.min + words.length),
                        )
                    } else {
                        val place = placeDictation(
                            noteText = body.text,
                            cursor = if (cursorPlaced) selection.start else null,
                            dictated = text,
                        )
                        TextFieldValue(
                            body.text.substring(0, place.index) + place.text + body.text.substring(place.index),
                            TextRange(place.index + place.text.length),
                        )
                    }
                }
            },
            onClose = { voiceOpen = false },
        )
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete this note?") },
            text = { Text("A copy stays in Trash on this phone. The deletion reaches GitHub and your PC at the next sync.") },
            confirmButton = {
                TextButton(
                    enabled = !finishing,
                    onClick = {
                        showDeleteConfirmation = false
                        finishing = true
                        scope.launch {
                            runCatching { viewModel.deleteAndFinishEditor(path) }
                                .onSuccess { onDone() }
                                .onFailure { finishing = false }
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancel") } },
        )
    }

    if (showSaveFailure) {
        AlertDialog(
            onDismissRequest = { showSaveFailure = false },
            title = { Text("Couldn’t save") },
            text = {
                Text("Your latest changes are still in the editor. Retry, or discard them and leave the editor.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showSaveFailure = false
                    requestFinish(finishLeavesScreen)
                }) { Text("Retry") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSaveFailure = false
                    finishing = true
                    scope.launch {
                        viewModel.discardEditorChangesAndFinish()
                        if (finishLeavesScreen || isNew) {
                            onDone()
                        } else {
                            finishing = false
                            editing = false
                        }
                    }
                }) { Text("Discard and leave") }
            },
        )
    }
}

/** The note's place in the repo, and the switch between Markdown and rich editing. */
@Composable
private fun EditorHeader(
    path: String,
    rich: Boolean,
    richSafe: Boolean,
    enabled: Boolean,
    onRichChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = path,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (richSafe) {
            TextButton(enabled = enabled, onClick = { onRichChange(!rich) }) {
                Text(if (rich) "Markdown" else "Rich text")
            }
        } else {
            Text(
                text = "Markdown only",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .semantics {
                        contentDescription = "Rich editing is off: it would change this note's Markdown"
                    },
            )
        }
    }
}

/** Shown while a note holds both versions of a clash, with one-tap ways to settle it. */
@Composable
private fun ConflictCard(enabled: Boolean, onKeep: (TextMerge.Keep) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Changed on the phone and on the PC", style = MaterialTheme.typography.titleSmall)
            Text(
                "Both versions are kept below, between the lines \"<<<<<<< phone\" and \">>>>>>> PC\". " +
                    "Pick one, keep both, or edit the text yourself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = enabled, onClick = { onKeep(TextMerge.Keep.PHONE) }) { Text("Keep phone's") }
                TextButton(enabled = enabled, onClick = { onKeep(TextMerge.Keep.PC) }) { Text("Keep PC's") }
                TextButton(enabled = enabled, onClick = { onKeep(TextMerge.Keep.BOTH) }) { Text("Keep both") }
            }
        }
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
                modifier = Modifier.semantics { contentDescription = "Could not save note. Retry" },
            )
        }
    }
}

/**
 * True when the rich editor gives back exactly this Markdown, so editing the note in it changes
 * only what the writer changes. Tables, front matter, and `<!-- -->` notes usually fail this.
 */
internal fun isRichSafe(markdown: String): Boolean {
    if (markdown.isBlank()) return true
    return runCatching {
        val state = RichTextState()
        state.setMarkdown(markdown)
        state.toMarkdown().trimEnd() == markdown.trimEnd()
    }.getOrDefault(false)
}

private const val EDITOR_PREFS = "editor"
private const val KEY_RICH = "rich"
