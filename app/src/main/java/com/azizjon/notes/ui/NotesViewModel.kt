package com.azizjon.notes.ui

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
import com.azizjon.notes.data.Note
import com.azizjon.notes.data.Notebook
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

data class BackupUiState(
    val config: GitHubBackupConfig = GitHubBackupConfig(),
    val lastBackupAt: Long = 0L,
    val inProgress: Boolean = false,
    val message: String? = null,
) {
    val configured: Boolean get() = config.configured
}

class NotesViewModel(
    private val repository: NotesRepository,
    private val backupSettings: GitHubBackupSettings,
    private val githubBackup: GitHubSqlBackupManager,
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

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun selectNotebook(id: Long?) {
        _selectedNotebookId.value = id
    }

    suspend fun load(id: Long): Note? = repository.getById(id)

    /** Persists a note into [notebookId]. [id] <= 0 creates a new note; otherwise updates it. */
    fun save(id: Long, title: String, content: String, notebookId: Long) {
        viewModelScope.launch {
            val base = if (id > 0) repository.getById(id) ?: Note() else Note()
            repository.save(
                base.copy(
                    id = if (id > 0) id else 0,
                    title = title.trim(),
                    content = content,
                    notebookId = notebookId,
                ),
            )
            scheduleAutoBackup()
        }
    }

    fun moveToTrash(id: Long) {
        viewModelScope.launch {
            repository.moveToTrash(id)
            scheduleAutoBackup()
        }
    }

    fun restore(note: Note) {
        viewModelScope.launch {
            repository.restore(note.id)
            scheduleAutoBackup()
        }
    }

    fun deleteForever(note: Note) {
        viewModelScope.launch {
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

    fun deleteNotebook(id: Long) {
        viewModelScope.launch {
            repository.deleteNotebook(id)
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
                    it.copy(
                        inProgress = false,
                        message = "GitHub backed up ${result.notes} note(s)",
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
                val restored = githubBackup.restore(config)
                repository.replaceBackup(restored.notebooks, restored.notes)
                refreshBackupState()
                _backupState.update {
                    it.copy(
                        inProgress = false,
                        message = "GitHub restored ${restored.notes.size} note(s)",
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
                NotesViewModel(app.repository, app.githubBackupSettings, app.githubSqlBackupManager)
            }
        }
    }
}
