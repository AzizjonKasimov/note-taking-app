package com.azizjon.notes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The built-in "Unfiled" notebook. It always exists (seeded on install / migration), is where
 * orphaned notes land when a notebook is deleted, and cannot itself be renamed or deleted.
 */
const val DEFAULT_NOTEBOOK_ID: Long = 1L
const val DEFAULT_NOTEBOOK_NAME: String = "Unfiled"

/** A notebook groups notes. [id] is 0 for a notebook that has not been persisted yet. */
@Entity(tableName = "notebooks")
data class Notebook(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
