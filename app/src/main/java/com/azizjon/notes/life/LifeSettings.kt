package com.azizjon.notes.life

import android.content.Context

/** Which repo the app opens, who commits, and how the last sync went. No secrets: see [TokenStore]. */
class LifeSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** "owner/name", shown in Settings. */
    var repoName: String?
        get() = prefs.getString("repo_name", null)
        set(value) = prefs.edit().putString("repo_name", value).apply()

    var remoteUrl: String?
        get() = prefs.getString("remote_url", null)
        set(value) = prefs.edit().putString("remote_url", value).apply()

    /** The GitHub login the token belongs to (sent as the user name; GitHub checks only the token). */
    var login: String?
        get() = prefs.getString("login", null)
        set(value) = prefs.edit().putString("login", value).apply()

    var authorName: String
        get() = prefs.getString("author_name", null) ?: "Notes app"
        set(value) = prefs.edit().putString("author_name", value).apply()

    /** GitHub's no-reply address for the account, so commits show as the user's without an email. */
    var authorEmail: String
        get() = prefs.getString("author_email", null) ?: "notes-app@users.noreply.github.com"
        set(value) = prefs.edit().putString("author_email", value).apply()

    var lastSyncAt: Long
        get() = prefs.getLong("last_sync_at", 0L)
        set(value) = prefs.edit().putLong("last_sync_at", value).apply()

    var lastSyncError: String?
        get() = prefs.getString("last_sync_error", null)
        set(value) = prefs.edit().putString("last_sync_error", value).apply()

    /** Set once the app's own notes written since they were moved by hand are in the repo. */
    var appNotesMoved: Boolean
        get() = prefs.getBoolean("app_notes_moved", false)
        set(value) = prefs.edit().putBoolean("app_notes_moved", value).commit().let { }

    fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        const val PREFS_NAME = "life"
    }
}
