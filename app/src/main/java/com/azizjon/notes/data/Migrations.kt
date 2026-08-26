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

/** v2 -> v3: retain deleted notes in Trash so they can be restored. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notes ADD COLUMN deletedAt INTEGER")
    }
}

/** v3 -> v4: add visual notebook marker metadata; existing notebooks use automatic initials. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notebooks ADD COLUMN markerType TEXT NOT NULL DEFAULT 'AUTO'")
        db.execSQL("ALTER TABLE notebooks ADD COLUMN markerColor INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE notebooks ADD COLUMN markerValue TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE notebooks ADD COLUMN cropLeft REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE notebooks ADD COLUMN cropTop REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE notebooks ADD COLUMN cropSize REAL NOT NULL DEFAULT 1")
    }
}
