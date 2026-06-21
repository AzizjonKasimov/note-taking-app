package com.azizjon.notes.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.azizjon.notes.data.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backs notes up to a user-chosen file via the Storage Access Framework — typically a file
 * in Google Drive picked through the system file picker. Requires no Google account setup,
 * API keys, or special permissions: the picker grants per-file access, which we persist so
 * later backups can overwrite the same file silently.
 */
class BackupManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)

    val backupUri: Uri?
        get() = prefs.getString(KEY_URI, null)?.let(Uri::parse)

    val lastBackupAt: Long
        get() = prefs.getLong(KEY_LAST, 0L)

    val isConfigured: Boolean
        get() = backupUri != null

    /** Remembers the chosen file and persists read/write access across reboots. */
    fun saveTarget(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        prefs.edit().putString(KEY_URI, uri.toString()).apply()
    }

    suspend fun backup(notes: List<Note>): Boolean = withContext(Dispatchers.IO) {
        val uri = backupUri ?: return@withContext false
        try {
            // "wt" = write + truncate, so a shorter export fully replaces the old contents.
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(serialize(notes).toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return@withContext false
            prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun restore(): List<Note>? = backupUri?.let { readFrom(it) }

    suspend fun readFrom(uri: Uri): List<Note>? = withContext(Dispatchers.IO) {
        try {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: return@withContext null
            deserialize(text)
        } catch (e: Exception) {
            null
        }
    }

    private fun serialize(notes: List<Note>): String {
        val array = JSONArray()
        notes.forEach { note ->
            array.put(
                JSONObject()
                    .put("id", note.id)
                    .put("title", note.title)
                    .put("content", note.content)
                    .put("createdAt", note.createdAt)
                    .put("updatedAt", note.updatedAt),
            )
        }
        return JSONObject()
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("notes", array)
            .toString()
    }

    private fun deserialize(text: String): List<Note> {
        val notes = JSONObject(text).optJSONArray("notes") ?: JSONArray()
        return (0 until notes.length()).map { i ->
            val o = notes.getJSONObject(i)
            Note(
                id = o.optLong("id", 0),
                title = o.optString("title", ""),
                content = o.optString("content", ""),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            )
        }
    }

    companion object {
        private const val KEY_URI = "backup_uri"
        private const val KEY_LAST = "last_backup_at"
    }
}
