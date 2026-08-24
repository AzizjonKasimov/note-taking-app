package com.azizjon.notes.backup

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class GitHubBackupConfig(
    val owner: String = GitHubBackupSettings.DEFAULT_OWNER,
    val repo: String = GitHubBackupSettings.DEFAULT_REPO,
    val branch: String = GitHubBackupSettings.DEFAULT_BRANCH,
    val path: String = GitHubBackupSettings.DEFAULT_PATH,
    val token: String = "",
    val autoBackup: Boolean = true,
) {
    val configured: Boolean
        get() = owner.isNotBlank() && repo.isNotBlank() && branch.isNotBlank() &&
            path.isNotBlank() && token.isNotBlank()
}

class GitHubBackupSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secrets = EncryptedSharedPreferences.create(
        context,
        SECRET_PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    val config: GitHubBackupConfig
        get() = GitHubBackupConfig(
            owner = prefs.getString(KEY_OWNER, DEFAULT_OWNER).orEmpty().ifBlank { DEFAULT_OWNER },
            repo = prefs.getString(KEY_REPO, DEFAULT_REPO).orEmpty().ifBlank { DEFAULT_REPO },
            branch = prefs.getString(KEY_BRANCH, DEFAULT_BRANCH).orEmpty().ifBlank { DEFAULT_BRANCH },
            path = prefs.getString(KEY_PATH, DEFAULT_PATH).orEmpty().ifBlank { DEFAULT_PATH },
            token = secrets.getString(KEY_TOKEN, "").orEmpty(),
            autoBackup = prefs.getBoolean(KEY_AUTO_BACKUP, true),
        )

    val lastBackupAt: Long
        get() = prefs.getLong(KEY_LAST_BACKUP_AT, 0L)

    fun save(config: GitHubBackupConfig) {
        prefs.edit()
            .putString(KEY_OWNER, config.owner.trim())
            .putString(KEY_REPO, config.repo.trim())
            .putString(KEY_BRANCH, config.branch.trim())
            .putString(KEY_PATH, config.path.trim().trimStart('/'))
            .putBoolean(KEY_AUTO_BACKUP, config.autoBackup)
            .apply()
        secrets.edit()
            .putString(KEY_TOKEN, config.token.trim())
            .apply()
    }

    fun markBackedUp() {
        prefs.edit().putLong(KEY_LAST_BACKUP_AT, System.currentTimeMillis()).apply()
    }

    companion object {
        const val DEFAULT_OWNER = "AzizjonKasimov"
        const val DEFAULT_REPO = "note-taking-app-data"
        const val DEFAULT_BRANCH = "main"
        const val DEFAULT_PATH = "notes.sql"

        private const val PREFS_NAME = "github_backup"
        const val SECRET_PREFS_NAME = "github_backup_secrets"

        private const val KEY_OWNER = "owner"
        private const val KEY_REPO = "repo"
        private const val KEY_BRANCH = "branch"
        private const val KEY_PATH = "path"
        private const val KEY_TOKEN = "token"
        private const val KEY_AUTO_BACKUP = "auto_backup"
        private const val KEY_LAST_BACKUP_AT = "last_backup_at"
    }
}
