package com.azizjon.notes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Note::class, Notebook::class], version = 4, exportSchema = false)
abstract class NotesDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao
    abstract fun notebookDao(): NotebookDao

    companion object {
        @Volatile
        private var INSTANCE: NotesDatabase? = null

        fun get(context: Context): NotesDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NotesDatabase::class.java,
                    "notes.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .addCallback(DefaultNotebookCallback)
                    .build()
                    .also { INSTANCE = it }
            }

        /**
         * Seeds the built-in "Unfiled" notebook on a fresh install. Upgrades are handled by
         * [MIGRATION_1_2] instead — onCreate does not fire on the migration path, so the row is
         * only inserted once.
         */
        private val DefaultNotebookCallback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "INSERT INTO notebooks (id, name, createdAt) " +
                        "VALUES ($DEFAULT_NOTEBOOK_ID, '$DEFAULT_NOTEBOOK_NAME', ${System.currentTimeMillis()})",
                )
            }
        }
    }
}
