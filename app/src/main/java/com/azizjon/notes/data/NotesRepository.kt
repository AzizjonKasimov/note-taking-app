package com.azizjon.notes.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Thin layer over the DAOs so the UI never touches Room types directly. */
class NotesRepository(private val db: NotesDatabase) {

    private val noteDao = db.noteDao()
    private val notebookDao = db.notebookDao()

    // ---- Notes ----

    /** Notes for [notebookId] (null = every notebook), optionally filtered by [query]. */
    fun notes(notebookId: Long?, query: String): Flow<List<Note>> {
        val q = query.trim()
        return when {
            notebookId == null && q.isBlank() -> noteDao.observeAll()
            notebookId == null -> noteDao.search(q)
            q.isBlank() -> noteDao.observeByNotebook(notebookId)
            else -> noteDao.searchInNotebook(notebookId, q)
        }
    }

    suspend fun getById(id: Long): Note? = noteDao.getById(id)

    /** Inserts or updates [note], stamping it with the current time. Returns the row id. */
    suspend fun save(note: Note): Long =
        noteDao.upsert(note.copy(updatedAt = System.currentTimeMillis()))

    suspend fun delete(note: Note) = noteDao.delete(note)

    suspend fun deleteById(id: Long) = noteDao.deleteById(id)

    suspend fun allNotes(): List<Note> = noteDao.getAll()

    // ---- Notebooks ----

    fun notebooks(): Flow<List<Notebook>> = notebookDao.observeAll()

    /** Notebook id -> note count, for the drawer badges. */
    fun notebookCounts(): Flow<Map<Long, Int>> =
        notebookDao.observeCounts().map { counts -> counts.associate { it.notebookId to it.count } }

    suspend fun allNotebooks(): List<Notebook> = notebookDao.getAll()

    suspend fun createNotebook(name: String): Long =
        notebookDao.upsert(Notebook(name = name.trim()))

    suspend fun renameNotebook(id: Long, name: String) {
        if (id == DEFAULT_NOTEBOOK_ID) return
        val existing = notebookDao.getById(id) ?: return
        notebookDao.upsert(existing.copy(name = name.trim()))
    }

    /** Deletes [id] and moves its notes to the default notebook. The default can't be deleted. */
    suspend fun deleteNotebook(id: Long) {
        if (id == DEFAULT_NOTEBOOK_ID) return
        db.withTransaction {
            noteDao.reassignNotebook(id, DEFAULT_NOTEBOOK_ID)
            notebookDao.deleteById(id)
        }
    }

    // ---- Backup / restore ----

    /**
     * Merges an imported backup. Notebooks are upserted (the default is left intact); notes are
     * merged by id keeping whichever copy is newer (last-write-wins), and any note pointing at an
     * unknown notebook is remapped to the default so it is never orphaned.
     */
    suspend fun importBackup(notebooks: List<Notebook>, notes: List<Note>) {
        db.withTransaction {
            val incomingNotebooks = notebooks.filter { it.id != DEFAULT_NOTEBOOK_ID }
            if (incomingNotebooks.isNotEmpty()) notebookDao.upsertAll(incomingNotebooks)

            val knownIds = notebookDao.getAll().mapTo(HashSet()) { it.id }
            val existing = noteDao.getAll().associateBy { it.id }
            val toWrite = notes
                .filter { note ->
                    val local = existing[note.id]
                    local == null || note.updatedAt > local.updatedAt
                }
                .map { note ->
                    if (note.notebookId in knownIds) note else note.copy(notebookId = DEFAULT_NOTEBOOK_ID)
                }
            if (toWrite.isNotEmpty()) noteDao.upsertAll(toWrite)
        }
    }
}
