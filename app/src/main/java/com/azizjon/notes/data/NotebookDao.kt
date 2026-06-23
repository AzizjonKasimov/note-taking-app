package com.azizjon.notes.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Number of notes in a notebook; powers the drawer count badges. */
data class NotebookCount(val notebookId: Long, val count: Int)

@Dao
interface NotebookDao {

    @Query("SELECT * FROM notebooks ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<Notebook>>

    @Query("SELECT * FROM notebooks ORDER BY createdAt ASC")
    suspend fun getAll(): List<Notebook>

    @Query("SELECT * FROM notebooks WHERE id = :id")
    suspend fun getById(id: Long): Notebook?

    @Query("SELECT notebookId, COUNT(*) AS count FROM notes GROUP BY notebookId")
    fun observeCounts(): Flow<List<NotebookCount>>

    @Upsert
    suspend fun upsert(notebook: Notebook): Long

    @Upsert
    suspend fun upsertAll(notebooks: List<Notebook>)

    @Query("DELETE FROM notebooks WHERE id = :id")
    suspend fun deleteById(id: Long)
}
