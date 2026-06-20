package com.azizjon.notes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.azizjon.notes.NotesApplication
import com.azizjon.notes.data.Note
import com.azizjon.notes.data.NotesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NotesViewModel(private val repository: NotesRepository) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val notes: StateFlow<List<Note>> =
        _query
            .flatMapLatest { repository.notes(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
        }
    }

    fun delete(note: Note) {
        viewModelScope.launch { repository.delete(note) }
    }

    fun deleteById(id: Long) {
        viewModelScope.launch { repository.deleteById(id) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as NotesApplication
                NotesViewModel(app.repository)
            }
        }
    }
}
