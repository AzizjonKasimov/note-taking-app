package com.azizjon.notes.data

/** Complete durable state for one editor revision: a note's path in the repo and its whole text. */
data class EditorSnapshot(
    val path: String,
    val content: String,
)
