package com.azizjon.notes.data

/** Complete durable state for one editor revision. */
data class EditorSnapshot(
    val noteId: Long,
    val title: String,
    val content: String,
    val notebookId: Long,
)
