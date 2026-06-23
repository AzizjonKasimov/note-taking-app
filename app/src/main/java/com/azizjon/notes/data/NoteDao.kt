package com.azizjon.notes.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Note>>

    @Query(
        "SELECT * FROM notes " +
            "WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' " +
            "ORDER BY updatedAt DESC",
    )
    fun search(query: String): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE notebookId = :notebookId ORDER BY updatedAt DESC")
    fun observeByNotebook(notebookId: Long): Flow<List<Note>>

    @Query(
        "SELECT * FROM notes " +
            "WHERE notebookId = :notebookId " +
            "AND (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') " +
            "ORDER BY updatedAt DESC",
    )
    fun searchInNotebook(notebookId: Long, query: String): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): Note?

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    suspend fun getAll(): List<Note>

    @Upsert
    suspend fun upsert(note: Note): Long

    @Upsert
    suspend fun upsertAll(notes: List<Note>)

    @Delete
    suspend fun delete(note: Note)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Moves every note in [source] to [target]; used when a notebook is deleted. */
    @Query("UPDATE notes SET notebookId = :target WHERE notebookId = :source")
    suspend fun reassignNotebook(source: Long, target: Long)
}
