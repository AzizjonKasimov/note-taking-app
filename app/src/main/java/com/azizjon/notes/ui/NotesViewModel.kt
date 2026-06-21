package com.azizjon.notes.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.azizjon.notes.NotesApplication
import com.azizjon.notes.backup.BackupManager
import com.azizjon.notes.data.Note
import com.azizjon.notes.data.NotesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackupUiState(
    val configured: Boolean = false,
    val lastBackupAt: Long = 0L,
    val inProgress: Boolean = false,
    val message: String? = null,
)

class NotesViewModel(
    private val repository: NotesRepository,
    private val backup: BackupManager,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val notes: StateFlow<List<Note>> =
        _query
            .flatMapLatest { repository.notes(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _backupState = MutableStateFlow(
        BackupUiState(configured = backup.isConfigured, lastBackupAt = backup.lastBackupAt),
    )
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()

    private var autoBackupJob: Job? = null

    fun onQueryChange(value: String) {
        _query.value = value
    }

    suspend fun load(id: Long): Note? = repository.getById(id)

    /** Persists a note. [id] <= 0 creates a new note; otherwise updates the existing one. */
    fun save(id: Long, title: String, content: String) {
        viewModelScope.launch {
            val base = if (id > 0) repository.getById(id) ?: Note() else Note()
            repository.save(
                base.copy(
                    id = if (id > 0) id else 0,
                    title = title.trim(),
                    content = content,
                ),
            )
            scheduleAutoBackup()
        }
    }

    fun delete(note: Note) {
        viewModelScope.launch {
            repository.delete(note)
            scheduleAutoBackup()
        }
    }

    fun deleteById(id: Long) {
        viewModelScope.launch {
            repository.deleteById(id)
            scheduleAutoBackup()
        }
    }

    // ---- Backup / restore ----

    /** Called after the user picks a Drive file location: persists access and backs up now. */
    fun onBackupTargetChosen(uri: Uri) {
        backup.saveTarget(uri)
        refreshBackupState()
        backupNow()
    }

    fun backupNow() {
        viewModelScope.launch {
            _backupState.update { it.copy(inProgress = true, message = null) }
            val ok = backup.backup(repository.allNotes())
            _backupState.update {
                it.copy(
                    inProgress = false,
                    configured = backup.isConfigured,
                    lastBackupAt = backup.lastBackupAt,
                    message = if (ok) "Backed up to Drive" else "Backup failed",
                )
            }
        }
    }

    /** Restore from the already-configured backup file. */
    fun restore() {
        viewModelScope.launch {
            _backupState.update { it.copy(inProgress = true, message = null) }
            val restored = backup.restore()
            if (restored != null) repository.importNotes(restored)
            _backupState.update {
                it.copy(
                    inProgress = false,
                    message = if (restored != null) "Restored ${restored.size} notes" else "Restore failed",
                )
            }
        }
    }

    /** Restore from a file the user picks manually, then remember it for future backups. */
    fun restoreFromUri(uri: Uri) {
        viewModelScope.launch {
            _backupState.update { it.copy(inProgress = true, message = null) }
            val restored = backup.readFrom(uri)
            if (restored != null) {
                repository.importNotes(restored)
                backup.saveTarget(uri)
            }
            refreshBackupState()
            _backupState.update {
                it.copy(
                    inProgress = false,
                    message = if (restored != null) "Restored ${restored.size} notes" else "Restore failed",
                )
            }
        }
    }

    fun consumeMessage() {
        _backupState.update { it.copy(message = null) }
    }

    /** Debounced: backs up a few seconds after the last change, if a target is configured. */
    private fun scheduleAutoBackup() {
        if (!backup.isConfigured) return
        autoBackupJob?.cancel()
        autoBackupJob = viewModelScope.launch {
            delay(3_000)
            backup.backup(repository.allNotes())
            refreshBackupState()
        }
    }

    private fun refreshBackupState() {
        _backupState.update {
            it.copy(configured = backup.isConfigured, lastBackupAt = backup.lastBackupAt)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as NotesApplication
                NotesViewModel(app.repository, app.backupManager)
            }
        }
    }
}
