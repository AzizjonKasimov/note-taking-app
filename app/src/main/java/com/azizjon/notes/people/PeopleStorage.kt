package com.azizjon.notes.people

/**
 * Every file People keeps on the phone. Android's own backup must leave each one out, as Network App
 * did by turning that backup off (Notes keeps it on for its settings and folder markers), and
 * `PeopleStorageTest` checks that both backup rule files name every one.
 *
 * Each name starts with `people`, so none can clash with Notes' own: Notes deletes the preferences
 * `github_backup` and `github_backup_secrets` at every start (its own backup until 2.2), and those were
 * Network App's names for the backup token and passphrase.
 */
object PeopleStorage {
    const val DATABASE = "people.db"
    const val BACKUP_PREFS = "people_backup"
    const val BACKUP_SECRET_PREFS = "people_backup_secrets"
    const val CAPTURE_DRAFT_PREFS = "people_capture_draft"
    const val INBOX_PREFS = "people_pc_inbox"
    const val CHECKIN_PREFS = "people_checkin_settings"
    const val ASSISTANT_PREFS = "people_assistant"

    /** The database's files: Room keeps a write-ahead log beside it. */
    val databaseFiles = listOf(DATABASE, "$DATABASE-wal", "$DATABASE-shm", "$DATABASE-journal")

    /** The shared preference files, by the names the backup rules give them (with `.xml`). */
    val preferenceFiles = listOf(
        BACKUP_PREFS,
        BACKUP_SECRET_PREFS,
        CAPTURE_DRAFT_PREFS,
        INBOX_PREFS,
        CHECKIN_PREFS,
        ASSISTANT_PREFS,
    ).map { "$it.xml" }
}
