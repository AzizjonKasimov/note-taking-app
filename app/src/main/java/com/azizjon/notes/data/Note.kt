package com.azizjon.notes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A single note. [id] is 0 for a note that has not been persisted yet. */
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
