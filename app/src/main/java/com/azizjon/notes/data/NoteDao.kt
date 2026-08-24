package com.azizjon.notes.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Note>>

    @Query(
        "SELECT * FROM notes " +
            "WHERE deletedAt IS NULL " +
            "AND (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') " +
            "ORDER BY updatedAt DESC",
    )
    fun search(query: String): Flow<List<Note>>

    @Query(
        "SELECT * FROM notes WHERE deletedAt IS NULL AND notebookId = :notebookId " +
            "ORDER BY updatedAt DESC",
    )
    fun observeByNotebook(notebookId: Long): Flow<List<Note>>

    @Query(
        "SELECT * FROM notes " +
            "WHERE deletedAt IS NULL AND notebookId = :notebookId " +
            "AND (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') " +
            "ORDER BY updatedAt DESC",
    )
    fun searchInNotebook(notebookId: Long, query: String): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: Long): Note?

    @Query("SELECT * FROM notes WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeDeleted(): Flow<List<Note>>

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    suspend fun getAll(): List<Note>

    @Upsert
    suspend fun upsert(note: Note): Long

    @Upsert
    suspend fun upsertAll(notes: List<Note>)

    @Query(
        "UPDATE notes SET deletedAt = :deletedAt, updatedAt = :deletedAt " +
            "WHERE id = :id AND deletedAt IS NULL",
    )
    suspend fun moveToTrash(id: Long, deletedAt: Long)

    @Query(
        "UPDATE notes SET deletedAt = NULL, updatedAt = :restoredAt " +
            "WHERE id = :id AND deletedAt IS NOT NULL",
    )
    suspend fun restore(id: Long, restoredAt: Long)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM notes WHERE deletedAt IS NOT NULL")
    suspend fun deleteAllDeleted()

    @Query("DELETE FROM notes")
    suspend fun deleteAll()

    /** Moves every note in [source] to [target]; used when a notebook is deleted. */
    @Query("UPDATE notes SET notebookId = :target WHERE notebookId = :source")
    suspend fun reassignNotebook(source: Long, target: Long)
}
