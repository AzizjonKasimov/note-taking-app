package com.azizjon.notes.data

import android.content.Context

data class ActiveEditorSession(
    val noteId: Long,
    val isNew: Boolean,
)

/**
 * The tiny durable breadcrumb used to resume an editor after process death.
 *
 * Writes deliberately use [android.content.SharedPreferences.Editor.commit]: entering or leaving
 * an editing session is rare, and the breadcrumb must be on disk before navigation can continue.
 */
class ActiveEditorSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): ActiveEditorSession? {
        val id = preferences.getLong(KEY_NOTE_ID, NO_NOTE_ID)
        if (id <= 0L) return null
        return ActiveEditorSession(id, preferences.getBoolean(KEY_IS_NEW, false))
    }

    fun write(session: ActiveEditorSession): Boolean =
        preferences.edit()
            .putLong(KEY_NOTE_ID, session.noteId)
            .putBoolean(KEY_IS_NEW, session.isNew)
            .commit()

    fun clear(): Boolean = preferences.edit().clear().commit()

    fun clearIf(noteId: Long): Boolean =
        if (read()?.noteId == noteId) clear() else true

    companion object {
        internal const val PREFS_NAME = "active_editor_session"
        private const val KEY_NOTE_ID = "note_id"
        private const val KEY_IS_NEW = "is_new"
        private const val NO_NOTE_ID = -1L
    }
}
