package com.azizjon.notes.data

import android.content.Context

data class ActiveEditorSession(
    /** The note's path in the repo. */
    val path: String,
    /** True for a note made in this session, which has no file until its first save. */
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
        val path = preferences.getString(KEY_PATH, null)?.takeIf { it.isNotBlank() } ?: return null
        return ActiveEditorSession(path, preferences.getBoolean(KEY_IS_NEW, false))
    }

    fun write(session: ActiveEditorSession): Boolean =
        preferences.edit()
            .clear()
            .putString(KEY_PATH, session.path)
            .putBoolean(KEY_IS_NEW, session.isNew)
            .commit()

    fun clear(): Boolean = preferences.edit().clear().commit()

    fun clearIf(path: String): Boolean =
        if (read()?.path == path) clear() else true

    companion object {
        internal const val PREFS_NAME = "active_editor_session"
        private const val KEY_PATH = "path"
        private const val KEY_IS_NEW = "is_new"
    }
}
