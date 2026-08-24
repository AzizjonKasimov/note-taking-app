package com.azizjon.notes.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A single note. [id] is 0 for a note that has not been persisted yet. */
@Entity(
    tableName = "notes",
    indices = [Index("notebookId")],
)
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val content: String = "",
    // defaultValue must match MIGRATION_1_2's `DEFAULT 1`; kept in sync with DEFAULT_NOTEBOOK_ID.
    @ColumnInfo(defaultValue = "1") val notebookId: Long = DEFAULT_NOTEBOOK_ID,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Non-null while the note is in Trash. */
    val deletedAt: Long? = null,
)
