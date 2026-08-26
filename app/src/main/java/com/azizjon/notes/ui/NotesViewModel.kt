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
import com.azizjon.notes.backup.BackupData
import com.azizjon.notes.backup.GitHubBackupConfig
import com.azizjon.notes.backup.GitHubBackupSettings
import com.azizjon.notes.backup.GitHubSqlBackupManager
import com.azizjon.notes.data.ActiveEditorSession
import com.azizjon.notes.data.ActiveEditorSessionStore
import com.azizjon.notes.data.DEFAULT_NOTEBOOK_ID
import com.azizjon.notes.data.EditorSnapshot
import com.azizjon.notes.data.Note
import com.azizjon.notes.data.Notebook
import com.azizjon.notes.data.NotebookAppearance
import com.azizjon.notes.data.NotebookImageStore
import com.azizjon.notes.data.NotebookMarkerType
import com.azizjon.notes.data.NormalizedCrop
import com.azizjon.notes.data.NotesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BackupUiState(
    val config: GitHubBackupConfig = GitHubBackupConfig(),
    val lastBackupAt: Long = 0L,
    val inProgress: Boolean = false,
    val message: String? = null,
) {
    val configured: Boolean get() = config.configured
}

sealed interface AppStartupState {
    data object Loading : AppStartupState
    data object NoteList : AppStartupState
    data class ResumeEditor(val noteId: Long) : AppStartupState
}

class NotesViewModel(
    private val repository: NotesRepository,
    private val backupSettings: GitHubBackupSettings,
    private val githubBackup: GitHubSqlBackupManager,
    private val imageStore: NotebookImageStore,
    private val editorSessionStore: ActiveEditorSessionStore,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Currently selected notebook; null means the "All notes" view. */
    private val _selectedNotebookId = MutableStateFlow<Long?>(null)
    val selectedNotebookId: StateFlow<Long?> = _selectedNotebookId.asStateFlow()

    val notebooks: StateFlow<List<Notebook>> =
        repository.notebooks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notebookCounts: StateFlow<Map<Long, Int>> =
        repository.notebookCounts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    val notes: StateFlow<List<Note>> =
        combine(_selectedNotebookId, _query) { id, q -> id to q }
            .flatMapLatest { (id, q) -> repository.notes(id, q) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val deletedNotes: StateFlow<List<Note>> =
        repository.deletedNotes()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _backupState = MutableStateFlow(
        BackupUiState(config = backupSettings.config, lastBackupAt = backupSettings.lastBackupAt),
    )
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()

    private var autoBackupJob: Job? = null

    private val autosave = EditorAutosaveCoordinator(
        scope = viewModelScope,
        writer = repository::saveDraft,
        onSuccessfulWrite = ::scheduleAutoBackup,
    )
    val editorSaveStatus: StateFlow<EditorSaveStatus> = autosave.status

    private val _activeEditorSession = MutableStateFlow(editorSessionStore.read())
    val activeEditorSession: StateFlow<ActiveEditorSession?> = _activeEditorSession.asStateFlow()

    private val _startupState = MutableStateFlow<AppStartupState>(AppStartupState.Loading)
    val startupState: StateFlow<AppStartupState> = _startupState.asStateFlow()

    private val finishEditorMutex = Mutex()
    @Volatile private var acceptingEditorSnapshots = false

    init {
        viewModelScope.launch { resolveStartupState() }
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun selectNotebook(id: Long?) {
        _selectedNotebookId.value = id
    }

    suspend fun load(id: Long): Note? = repository.getById(id)

    fun currentEditorSnapshot(noteId: Long): EditorSnapshot? =
        autosave.latestSnapshot()?.takeIf { it.noteId == noteId }

    /** Creates the database row and durable session breadcrumb before editor navigation. */
    suspend fun createDraft(): Long {
        val draft = repository.createDraft(_selectedNotebookId.value ?: DEFAULT_NOTEBOOK_ID)
        val session = ActiveEditorSession(noteId = draft.id, isNew = true)
        if (!editorSessionStore.write(session)) {
            repository.discardEmptyDraft(draft.id)
            error("Could not start an editing session")
        }
        _activeEditorSession.value = session
        return draft.id
    }

    /** Starts observing only after the screen has loaded a real note snapshot. */
    fun beginEditing(noteId: Long, isNew: Boolean, snapshot: EditorSnapshot): Boolean {
        require(noteId > 0L && snapshot.noteId == noteId)
        val session = ActiveEditorSession(noteId, isNew)
        if (!editorSessionStore.write(session)) {
            autosave.reportError()
            return false
        }
        _activeEditorSession.value = session
        // A configuration change reuses the live coordinator; resetting its revision counter
        // while a Room write is in flight could make later revisions look older.
        if (autosave.latestSnapshot()?.noteId != noteId) autosave.begin(snapshot)
        acceptingEditorSnapshots = true
        return true
    }

    fun submitEditorSnapshot(snapshot: EditorSnapshot) {
        if (acceptingEditorSnapshots && _activeEditorSession.value?.noteId == snapshot.noteId) {
            autosave.submit(snapshot)
        }
    }

    /** Flushes Room, cleans an untouched new draft, then durably ends the resumable session. */
    suspend fun flushAndFinishEditor(): Boolean = finishEditorMutex.withLock {
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
            if (session.isNew) repository.discardEmptyDraft(session.noteId)
        } catch (_: Exception) {
            editorSessionStore.write(session)
            acceptingEditorSnapshots = true
            autosave.reportError()
            return@withLock false
        }
        _activeEditorSession.value = null
        autosave.endSession()
        if (session.isNew) scheduleAutoBackup()
        true
    }

    fun retryEditorSave() {
        viewModelScope.launch { autosave.retry() }
    }

    /** Flushes without ending the session, used when Android backgrounds the app. */
    fun flushEditorOnStop() {
        viewModelScope.launch { autosave.flush() }
    }

    /** Explicitly abandons only the unsaved in-memory revision and leaves the last Room value. */
    suspend fun discardEditorChangesAndFinish() = finishEditorMutex.withLock {
        val session = _activeEditorSession.value
        acceptingEditorSnapshots = false
        autosave.endSession()
        if (session?.isNew == true) {
            repository.discardEmptyDraft(session.noteId)
            scheduleAutoBackup()
        }
        editorSessionStore.clear()
        _activeEditorSession.value = null
    }

    /** Waits out any in-flight write so it cannot recreate the note after the trash operation. */
    suspend fun moveToTrashAndFinishEditor(id: Long) = finishEditorMutex.withLock {
        acceptingEditorSnapshots = false
        autosave.endSession()
        repository.moveToTrash(id)
        editorSessionStore.clearIf(id)
        if (_activeEditorSession.value?.noteId == id) _activeEditorSession.value = null
        scheduleAutoBackup()
    }

    fun moveToTrash(id: Long) {
        viewModelScope.launch {
            clearEditorSessionIf(id)
            repository.moveToTrash(id)
            scheduleAutoBackup()
        }
    }

    fun restore(note: Note) {
        viewModelScope.launch {
            clearEditorSessionIf(note.id)
            repository.restore(note.id)
            scheduleAutoBackup()
        }
    }

    fun deleteForever(note: Note) {
        viewModelScope.launch {
            clearEditorSessionIf(note.id)
            repository.deleteForever(note.id)
            scheduleAutoBackup()
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            repository.emptyTrash()
            scheduleAutoBackup()
        }
    }

    // ---- Notebooks ----

    fun createNotebook(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createNotebook(name)
            scheduleAutoBackup()
        }
    }

    fun renameNotebook(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.renameNotebook(id, name)
            scheduleAutoBackup()
        }
    }

    fun updateNotebookAppearance(id: Long, appearance: NotebookAppearance) {
        viewModelScope.launch {
            repository.updateNotebookAppearance(id, appearance)
            if (appearance.type != NotebookMarkerType.CUSTOM_PHOTO) imageStore.delete(id)
            scheduleAutoBackup()
        }
    }

    suspend fun decodePickedPhoto(uri: Uri): Bitmap = imageStore.decodePickedPhoto(uri)

    suspend fun loadCustomPhotoSource(id: Long): Bitmap? = imageStore.loadSource(id)

    suspend fun saveCustomPhoto(id: Long, source: Bitmap, crop: NormalizedCrop) {
        imageStore.saveCustomPhoto(id, source, crop)
        repository.updateNotebookAppearance(
            id,
            NotebookAppearance(type = NotebookMarkerType.CUSTOM_PHOTO, crop = crop),
        )
        scheduleAutoBackup()
    }

    fun deleteNotebook(id: Long) {
        viewModelScope.launch {
            repository.deleteNotebook(id)
            imageStore.delete(id)
            // Fall back to "All notes" if the deleted notebook was the active view.
            if (_selectedNotebookId.value == id) _selectedNotebookId.value = null
            scheduleAutoBackup()
        }
    }

    // ---- Backup / restore ----

    fun saveBackupSettings(config: GitHubBackupConfig) {
        backupSettings.save(config)
        refreshBackupState()
        _backupState.update { it.copy(message = "GitHub backup settings saved") }
    }

    fun backupNow() {
        viewModelScope.launch {
            if (_backupState.value.inProgress) return@launch
            val config = backupSettings.config
            _backupState.update { it.copy(inProgress = true, message = null) }
            try {
                val result = githubBackup.backup(config, snapshot())
                refreshBackupState()
                _backupState.update {
                    val warning = if (result.cleanupWarnings > 0) {
                        "; ${result.cleanupWarnings} old image(s) could not be cleaned up"
                    } else {
                        ""
                    }
                    it.copy(
                        inProgress = false,
                        message = "GitHub backed up ${result.notes} note(s) and ${result.imagesUploaded} changed image(s)$warning",
                    )
                }
            } catch (e: Exception) {
                _backupState.update {
                    it.copy(
                        inProgress = false,
                        message = e.message ?: e::class.java.simpleName,
                    )
                }
            }
        }
    }

    /** Restore from the configured GitHub SQL backup. */
    fun restore() {
        viewModelScope.launch {
            if (_backupState.value.inProgress) return@launch
            val config = backupSettings.config
            _backupState.update { it.copy(inProgress = true, message = null) }
            try {
                clearEditorSession()
                val restored = githubBackup.restore(config)
                val prepared = imageStore.prepareRestore(restored.data.notebooks, restored.media)
                try {
                    repository.replaceBackup(prepared.notebooks, restored.data.notes)
                    imageStore.commitRestore(prepared)
                } catch (e: Exception) {
                    imageStore.discardRestore(prepared)
                    throw e
                }
                _selectedNotebookId.value = null
                refreshBackupState()
                _backupState.update {
                    val warningCount = restored.mediaDownloadWarnings + prepared.fallbackCount
                    val warning = when {
                        warningCount > 0 -> "; $warningCount notebook photo(s) fell back to initials"
                        prepared.missingSourceCount > 0 -> "; ${prepared.missingSourceCount} marker source(s) must be reselected to crop again"
                        else -> ""
                    }
                    it.copy(
                        inProgress = false,
                        message = "GitHub restored ${restored.data.notes.size} note(s)$warning",
                    )
                }
            } catch (e: Exception) {
                _backupState.update {
                    it.copy(
                        inProgress = false,
                        message = e.message ?: e::class.java.simpleName,
                    )
                }
            }
        }
    }

    fun consumeMessage() {
        _backupState.update { it.copy(message = null) }
    }

    private suspend fun snapshot(): BackupData =
        BackupData(notebooks = repository.allNotebooks(), notes = repository.allNotes())

    private suspend fun resolveStartupState() {
        val session = editorSessionStore.read()
        if (session == null) {
            _activeEditorSession.value = null
            _startupState.value = AppStartupState.NoteList
            return
        }

        val note = repository.getById(session.noteId)
        if (note == null || (session.isNew && note.title.isBlank() && note.content.isBlank())) {
            if (session.isNew) repository.discardEmptyDraft(session.noteId)
            editorSessionStore.clear()
            _activeEditorSession.value = null
            _startupState.value = AppStartupState.NoteList
            return
        }

        _activeEditorSession.value = session
        _startupState.value = AppStartupState.ResumeEditor(session.noteId)
    }

    private suspend fun clearEditorSessionIf(noteId: Long) {
        if (_activeEditorSession.value?.noteId == noteId || editorSessionStore.read()?.noteId == noteId) {
            clearEditorSession()
        }
    }

    private suspend fun clearEditorSession() {
        acceptingEditorSnapshots = false
        autosave.endSession()
        editorSessionStore.clear()
        _activeEditorSession.value = null
    }

    /** Debounced: backs up a few seconds after the last change, if a target is configured. */
    private fun scheduleAutoBackup() {
        val config = backupSettings.config
        if (!(config.configured && config.autoBackup)) return
        autoBackupJob?.cancel()
        autoBackupJob = viewModelScope.launch {
            delay(3_000)
            runCatching { githubBackup.backup(config, snapshot()) }
            refreshBackupState()
        }
    }

    private fun refreshBackupState() {
        _backupState.update {
            it.copy(config = backupSettings.config, lastBackupAt = backupSettings.lastBackupAt)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as NotesApplication
                NotesViewModel(
                    app.repository,
                    app.githubBackupSettings,
                    app.githubSqlBackupManager,
                    app.notebookImageStore,
                    app.activeEditorSessionStore,
                )
            }
        }
    }
}
