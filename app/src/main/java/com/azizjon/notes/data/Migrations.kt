package com.azizjon.notes.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2: introduce notebooks.
 *
 * Creates the `notebooks` table and the built-in "Unfiled" notebook, adds `notes.notebookId`
 * (every existing note defaults to Unfiled so nothing is lost), and indexes it. The index name
 * must match the one Room generates for `@Index("notebookId")` on [Note].
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS notebooks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "name TEXT NOT NULL, " +
                "createdAt INTEGER NOT NULL)",
        )
        db.execSQL(
            "INSERT INTO notebooks (id, name, createdAt) " +
                "VALUES ($DEFAULT_NOTEBOOK_ID, '$DEFAULT_NOTEBOOK_NAME', ${System.currentTimeMillis()})",
        )
        db.execSQL(
            "ALTER TABLE notes ADD COLUMN notebookId INTEGER NOT NULL DEFAULT $DEFAULT_NOTEBOOK_ID",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_notebookId ON notes (notebookId)")
    }
}
