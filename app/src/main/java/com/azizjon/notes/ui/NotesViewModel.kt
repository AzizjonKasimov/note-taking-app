package com.azizjon.notes.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.azizjon.notes.NotesApplication
import com.azizjon.notes.data.ActiveEditorSession
import com.azizjon.notes.data.ActiveEditorSessionStore
import com.azizjon.notes.data.EditorSnapshot
import com.azizjon.notes.data.Notebook
import com.azizjon.notes.data.NotebookAppearance
import com.azizjon.notes.data.NotebookImageStore
import com.azizjon.notes.data.NotebookMarkerType
import com.azizjon.notes.data.NormalizedCrop
import com.azizjon.notes.life.AppNotesMove
import com.azizjon.notes.life.Connection
import com.azizjon.notes.life.FolderAppearanceStore
import com.azizjon.notes.life.LifeRepository
import com.azizjon.notes.life.NoteEntry
import com.azizjon.notes.life.SyncStatus
import com.azizjon.notes.life.TrashEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A top-level folder of the repo, shown as a notebook. */
data class FolderItem(val path: String, val notebook: Notebook, val count: Int)

sealed interface AppStartupState {
    data object Loading : AppStartupState
    data object NoteList : AppStartupState
    data class ResumeEditor(val path: String, val isNew: Boolean) : AppStartupState
}

/** Text the open editor should take in, because a sync or a merge changed the note under it. */
data class EditorTakeIn(val forText: String, val diskText: String)

class NotesViewModel(
    private val life: LifeRepository,
    private val appearance: FolderAppearanceStore,
    private val imageStore: NotebookImageStore,
    private val editorSessionStore: ActiveEditorSessionStore,
) : ViewModel() {

    val connection: StateFlow<Connection> = life.connection
    val syncStatus: StateFlow<SyncStatus> = life.sync
    val trashed: StateFlow<List<TrashEntry>> = life.trashed
    val movedAppNotes: StateFlow<Int> = life.movedAppNotes
    /** Paths a sync rewrote on the phone. */
    val changedPaths: SharedFlow<Set<String>> = life.changed
    val repoName: String? get() = life.repoName

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** The folder shown; null means "All notes". */
    private val _selectedFolder = MutableStateFlow<String?>(null)
    val selectedFolder: StateFlow<String?> = _selectedFolder.asStateFlow()

    private val appearanceVersion = MutableStateFlow(0)

    val folders: StateFlow<List<FolderItem>> =
        combine(life.notes, appearanceVersion) { notes, _ ->
            val all = notes.orEmpty()
            all.filter { it.folder.isNotEmpty() }
                .groupBy { it.folder }
                .toSortedMap()
                .map { (path, list) ->
                    val label = all.firstOrNull { it.path == "$path/README.md" }?.title
                        ?.takeIf { it.length <= 40 } ?: path
                    FolderItem(path, appearance.notebook(path, label), list.size)
                }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The notes of the chosen folder that match the search; null while the list is first read. */
    val notes: StateFlow<List<NoteEntry>?> =
        combine(life.notes, _selectedFolder, _query) { all, folder, q ->
            val needle = q.trim()
            all?.filter { note ->
                (folder == null || note.folder == folder) &&
                    (needle.isEmpty() || note.title.contains(needle, ignoreCase = true) ||
                        note.text.contains(needle, ignoreCase = true))
            }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val totalCount: StateFlow<Int> = life.notes.map { it.orEmpty().size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _startupState = MutableStateFlow<AppStartupState>(AppStartupState.Loading)
    val startupState: StateFlow<AppStartupState> = _startupState.asStateFlow()

    // ---- The open editor ----

    private val _activeEditorSession = MutableStateFlow(editorSessionStore.read())
    val activeEditorSession: StateFlow<ActiveEditorSession?> = _activeEditorSession.asStateFlow()

    /** The text the editor's current text grew from: what it loaded, or what it last saved. */
    @Volatile private var editorBase: String? = null

    private val _editorTakeIn = MutableSharedFlow<EditorTakeIn>(extraBufferCapacity = 4)
    val editorTakeIn: SharedFlow<EditorTakeIn> = _editorTakeIn.asSharedFlow()

    private val autosave = EditorAutosaveCoordinator(
        scope = viewModelScope,
        writer = ::writeEditorSnapshot,
        onSuccessfulWrite = {},
    )
    val editorSaveStatus: StateFlow<EditorSaveStatus> = autosave.status

    private val finishEditorMutex = Mutex()
    @Volatile private var acceptingEditorSnapshots = false

    init {
        viewModelScope.launch { resolveStartupState() }
        viewModelScope.launch {
            life.changed.collect { paths ->
                val path = _activeEditorSession.value?.path
                if (path != null && path in paths) takeInDiskText(path)
            }
        }
        life.start()
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun selectFolder(path: String?) {
        _selectedFolder.value = path
    }

    fun syncNow() {
        viewModelScope.launch { life.sync() }
    }

    suspend fun connect(token: String, repo: String?) = life.connect(token, repo)

    suspend fun replaceToken(token: String) = life.replaceToken(token)

    suspend fun hasUnsyncedChanges(): Boolean = life.hasUnsyncedChanges()

    suspend fun disconnect() {
        clearEditorSession()
        life.disconnect()
    }

    fun consumeMovedAppNotes() = life.consumeMovedAppNotes()

    // ---- Editing ----

    suspend fun load(path: String): String? = life.read(path)

    fun currentEditorSnapshot(path: String): EditorSnapshot? =
        autosave.latestSnapshot()?.takeIf { it.path == path }

    /** A path for a new note in the folder on screen (the inbox from "All notes"), and its session. */
    suspend fun startNewNote(): String {
        val folder = _selectedFolder.value ?: AppNotesMove.INBOX
        val path = life.newNotePath(folder)
        val session = ActiveEditorSession(path, isNew = true)
        if (!editorSessionStore.write(session)) error("Could not start an editing session")
        _activeEditorSession.value = session
        return path
    }

    /** Starts saving the editor's changes; [snapshot] is the text as loaded. */
    fun beginEditing(path: String, isNew: Boolean, snapshot: EditorSnapshot): Boolean {
        require(snapshot.path == path)
        val session = ActiveEditorSession(path, isNew)
        if (!editorSessionStore.write(session)) {
            autosave.reportError()
            return false
        }
        _activeEditorSession.value = session
        // A configuration change reuses the live coordinator; restarting it while a write is in
        // flight could make later revisions look older.
        if (autosave.latestSnapshot()?.path != path) {
            autosave.begin(snapshot)
            editorBase = snapshot.content
        }
        acceptingEditorSnapshots = true
        return true
    }

    fun submitEditorSnapshot(snapshot: EditorSnapshot) {
        if (acceptingEditorSnapshots && _activeEditorSession.value?.path == snapshot.path) {
            autosave.submit(snapshot)
        }
    }

    /** The editor took in [diskText]: it is now both what the editor shows and what is on disk. */
    fun tookIn(path: String, diskText: String) {
        if (_activeEditorSession.value?.path != path) return
        editorBase = diskText
        autosave.begin(EditorSnapshot(path, diskText))
    }

    private suspend fun writeEditorSnapshot(snapshot: EditorSnapshot) {
        val base = editorBase
        val result = life.writeFromEditor(snapshot.path, snapshot.content, base)
        // The editor's next text grows from this one, whatever the file now holds.
        editorBase = snapshot.content
        if (result.diskText != snapshot.content) {
            _editorTakeIn.emit(EditorTakeIn(forText = snapshot.content, diskText = result.diskText))
        }
    }

    /** A sync changed the open note: show the new text, unless a save is on its way (it will merge). */
    private suspend fun takeInDiskText(path: String) {
        if (autosave.status.value != EditorSaveStatus.SAVED) return
        val base = editorBase ?: return
        val disk = life.read(path) ?: return
        if (disk != base) _editorTakeIn.emit(EditorTakeIn(forText = base, diskText = disk))
    }

    /** Saves, ends the session, drops an empty new note, and names a new note after its title. */
    suspend fun flushAndFinishEditor(title: String?): Boolean = finishEditorMutex.withLock {
        val session = _activeEditorSession.value ?: return@withLock true
        acceptingEditorSnapshots = false
        if (autosave.flush().isFailure) {
            acceptingEditorSnapshots = true
            return@withLock false
        }
        if (!editorSessionStore.clear()) {
            acceptingEditorSnapshots = true
            autosave.reportError()
            return@withLock false
        }
        try {
            if (session.isNew) {
                val text = life.read(session.path)
                if (text != null && text.isBlank()) {
                    life.discardBlank(session.path)
                } else if (text != null && !title.isNullOrBlank()) {
                    life.nameAfterTitle(session.path, title)
                }
            }
        } catch (_: Exception) {
            // The note is saved; only its tidy-up failed.
        }
        _activeEditorSession.value = null
        editorBase = null
        autosave.endSession()
        life.syncSoon(delaySeconds = 3)
        true
    }

    fun retryEditorSave() {
        viewModelScope.launch { autosave.retry() }
    }

    /** Flushes without ending the session, used when Android backgrounds the app. */
    fun flushEditorOnStop() {
        viewModelScope.launch { autosave.flush() }
    }

    /** Explicitly abandons only the unsaved in-memory revision and leaves the file as last saved. */
    suspend fun discardEditorChangesAndFinish() = finishEditorMutex.withLock {
        acceptingEditorSnapshots = false
        autosave.endSession()
        editorSessionStore.clear()
        _activeEditorSession.value = null
        editorBase = null
    }

    /** Deletes the open note (a copy stays in Trash), after any save in flight. */
    suspend fun deleteAndFinishEditor(path: String) = finishEditorMutex.withLock {
        acceptingEditorSnapshots = false
        autosave.endSession()
        life.delete(path)
        editorSessionStore.clearIf(path)
        if (_activeEditorSession.value?.path == path) _activeEditorSession.value = null
        editorBase = null
    }

    // ---- Trash ----

    fun restore(entry: TrashEntry) {
        viewModelScope.launch { life.restore(entry) }
    }

    fun deleteForever(entry: TrashEntry) {
        viewModelScope.launch { life.deleteForever(entry) }
    }

    fun emptyTrash() {
        viewModelScope.launch { life.emptyTrash() }
    }

    // ---- Folders ----

    fun createFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            life.createFolder(name)?.let { _selectedFolder.value = it }
        }
    }

    private fun folderPath(id: Long): String? = appearance.pathFor(id)

    fun updateNotebookAppearance(id: Long, value: NotebookAppearance) {
        val path = folderPath(id) ?: return
        viewModelScope.launch {
            appearance.update(path, value)
            if (value.type != NotebookMarkerType.CUSTOM_PHOTO) imageStore.delete(id)
            appearanceVersion.value++
        }
    }

    suspend fun decodePickedPhoto(uri: Uri): Bitmap = imageStore.decodePickedPhoto(uri)

    suspend fun loadCustomPhotoSource(id: Long): Bitmap? = imageStore.loadSource(id)

    suspend fun saveCustomPhoto(id: Long, source: Bitmap, crop: NormalizedCrop) {
        val path = folderPath(id) ?: return
        imageStore.saveCustomPhoto(id, source, crop)
        appearance.update(path, NotebookAppearance(type = NotebookMarkerType.CUSTOM_PHOTO, crop = crop))
        appearanceVersion.value++
    }

    // ---- Start-up ----

    private suspend fun resolveStartupState() {
        val session = editorSessionStore.read()
        if (session == null || !life.isCloned()) {
            clearEditorSession()
            _startupState.value = AppStartupState.NoteList
            return
        }
        val text = life.read(session.path)
        if (text == null && !session.isNew) {
            clearEditorSession()
            _startupState.value = AppStartupState.NoteList
            return
        }
        _activeEditorSession.value = session
        _startupState.value = AppStartupState.ResumeEditor(session.path, session.isNew)
    }

    private suspend fun clearEditorSession() {
        acceptingEditorSnapshots = false
        autosave.endSession()
        editorSessionStore.clear()
        _activeEditorSession.value = null
        editorBase = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as NotesApplication
                NotesViewModel(
                    app.life,
                    app.folderAppearance,
                    app.notebookImageStore,
                    app.activeEditorSessionStore,
                )
            }
        }
    }
}
