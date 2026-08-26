package com.azizjon.notes

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.azizjon.notes.data.DEFAULT_NOTEBOOK_ID
import com.azizjon.notes.data.DEFAULT_NOTEBOOK_NAME
import com.azizjon.notes.data.MIGRATION_1_2
import com.azizjon.notes.data.MIGRATION_2_3
import com.azizjon.notes.data.MIGRATION_3_4
import com.azizjon.notes.data.NotebookMarkerType
import com.azizjon.notes.data.NotesDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the v1 -> v4 migration on a hand-built v1 database: every existing note must survive and
 * land in the default "Unfiled" notebook, and opening through Room (which validates the resulting
 * schema) must not throw — i.e. a real update on the user's phone preserves notes and won't crash.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val testDb = "migration-test.db"
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun migrate1to4_preservesNotes_assignsUnfiled_andKeepsNotesActive() {
        context.deleteDatabase(testDb)

        // Build a v1 database exactly as Room generated it for schema version 1, with two notes.
        val dbFile = context.getDatabasePath(testDb)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile.path, null).apply {
            execSQL(
                "CREATE TABLE notes (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "title TEXT NOT NULL, content TEXT NOT NULL, " +
                    "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
            )
            execSQL("INSERT INTO notes (title, content, createdAt, updatedAt) VALUES ('Old A', 'aaa', 100, 100)")
            execSQL("INSERT INTO notes (title, content, createdAt, updatedAt) VALUES ('Old B', 'bbb', 200, 200)")
            version = 1
            close()
        }

        // Open through Room with both migrations and trigger final-schema validation.
        val db = Room.databaseBuilder(context, NotesDatabase::class.java, testDb)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
        try {
            val notes = runBlocking { db.noteDao().getAll() }
            assertEquals("both notes survive the migration", 2, notes.size)
            assertTrue(
                "every migrated note is reassigned to the default notebook",
                notes.all { it.notebookId == DEFAULT_NOTEBOOK_ID },
            )
            assertEquals(
                "note content is intact",
                setOf("aaa", "bbb"),
                notes.map { it.content }.toSet(),
            )
            assertTrue("migrated notes remain active", notes.all { it.deletedAt == null })

            val notebooks = runBlocking { db.notebookDao().getAll() }
            assertTrue(
                "the default Unfiled notebook exists after migrating",
                notebooks.any { it.id == DEFAULT_NOTEBOOK_ID && it.name == DEFAULT_NOTEBOOK_NAME },
            )
            assertTrue(
                "migrated notebooks use automatic markers",
                notebooks.all { it.markerType == NotebookMarkerType.AUTO.name },
            )
        } finally {
            db.close()
            context.deleteDatabase(testDb)
        }
    }

    @Test
    fun migrate3to4_addsAutomaticAppearance_withoutChangingNotebookData() {
        context.deleteDatabase(testDb)
        val dbFile = context.getDatabasePath(testDb)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile.path, null).apply {
            execSQL(
                "CREATE TABLE notes (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "title TEXT NOT NULL, content TEXT NOT NULL, notebookId INTEGER NOT NULL DEFAULT 1, " +
                    "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER)",
            )
            execSQL("CREATE INDEX index_notes_notebookId ON notes (notebookId)")
            execSQL(
                "CREATE TABLE notebooks (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL)",
            )
            execSQL("INSERT INTO notebooks (id,name,createdAt) VALUES (1,'Unfiled',10)")
            execSQL("INSERT INTO notebooks (id,name,createdAt) VALUES (2,'Work',20)")
            execSQL(
                "INSERT INTO notes (id,title,content,notebookId,createdAt,updatedAt,deletedAt) " +
                    "VALUES (1,'Plan','Body',2,30,40,NULL)",
            )
            version = 3
            close()
        }

        val db = Room.databaseBuilder(context, NotesDatabase::class.java, testDb)
            .addMigrations(MIGRATION_3_4)
            .build()
        try {
            val notebooks = runBlocking { db.notebookDao().getAll() }
            assertEquals(listOf("Unfiled", "Work"), notebooks.map { it.name })
            assertTrue(notebooks.all { it.markerType == NotebookMarkerType.AUTO.name })
            assertTrue(notebooks.all { it.cropSize == 1f })
            assertEquals("Body", runBlocking { db.noteDao().getAll() }.single().content)
        } finally {
            db.close()
            context.deleteDatabase(testDb)
        }
    }
}
