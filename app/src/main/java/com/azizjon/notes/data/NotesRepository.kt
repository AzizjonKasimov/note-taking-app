package com.azizjon.notes.data

import kotlinx.coroutines.flow.Flow

/** Thin layer over [NoteDao] so the UI never touches Room types directly. */
class NotesRepository(private val dao: NoteDao) {

    fun notes(query: String): Flow<List<Note>> =
        if (query.isBlank()) dao.observeAll() else dao.search(query.trim())

    suspend fun getById(id: Long): Note? = dao.getById(id)

    /** Inserts or updates [note], stamping it with the current time. Returns the row id. */
    suspend fun save(note: Note): Long =
        dao.upsert(note.copy(updatedAt = System.currentTimeMillis()))

    suspend fun delete(note: Note) = dao.delete(note)

    suspend fun deleteById(id: Long) = dao.deleteById(id)
}
