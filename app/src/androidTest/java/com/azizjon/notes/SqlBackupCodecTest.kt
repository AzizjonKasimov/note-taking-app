package com.azizjon.notes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.azizjon.notes.backup.BackupData
import com.azizjon.notes.backup.SqlBackupCodec
import com.azizjon.notes.data.DEFAULT_NOTEBOOK_ID
import com.azizjon.notes.data.DEFAULT_NOTEBOOK_NAME
import com.azizjon.notes.data.Note
import com.azizjon.notes.data.Notebook
import com.azizjon.notes.data.NotebookMarkerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqlBackupCodecTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun roundTrip_preservesTrashState() {
        val data = BackupData(
            notebooks = listOf(
                Notebook(id = DEFAULT_NOTEBOOK_ID, name = DEFAULT_NOTEBOOK_NAME, createdAt = 10),
            ),
            notes = listOf(
                Note(id = 1, title = "Active", createdAt = 100, updatedAt = 200),
                Note(
                    id = 2,
                    title = "Trashed",
                    createdAt = 300,
                    updatedAt = 500,
                    deletedAt = 500,
                ),
            ),
        )

        val restored = SqlBackupCodec.deserialize(context, SqlBackupCodec.serialize(data))

        assertEquals(2, restored.notes.size)
        assertNull(restored.notes.single { it.id == 1L }.deletedAt)
        assertEquals(500L, restored.notes.single { it.id == 2L }.deletedAt)
    }

    @Test
    fun deserialize_backupWithoutTrashColumn_keepsNotesActive() {
        val legacySql = """
            CREATE TABLE notes (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                notebookId INTEGER NOT NULL DEFAULT 1,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            );
            INSERT INTO notes (id,title,content,notebookId,createdAt,updatedAt)
            VALUES (7,'Legacy','Still here',1,100,200);
        """.trimIndent()

        val restored = SqlBackupCodec.deserialize(context, legacySql)

        assertEquals("Legacy", restored.notes.single().title)
        assertNull(restored.notes.single().deletedAt)
    }

    @Test
    fun roundTrip_preservesNotebookAppearance() {
        val notebook = Notebook(
            id = 9,
            name = "Ideas",
            markerType = NotebookMarkerType.EMOJI.name,
            markerColor = 6,
            markerValue = "💡",
            cropLeft = 0.2f,
            cropTop = 0.1f,
            cropSize = 0.6f,
        )

        val restored = SqlBackupCodec.deserialize(
            context,
            SqlBackupCodec.serialize(BackupData(notebooks = listOf(notebook), notes = emptyList())),
        ).notebooks.single { it.id == 9L }

        assertEquals(NotebookMarkerType.EMOJI.name, restored.markerType)
        assertEquals(6, restored.markerColor)
        assertEquals("💡", restored.markerValue)
        assertEquals(0.2f, restored.cropLeft)
        assertEquals(0.1f, restored.cropTop)
        assertEquals(0.6f, restored.cropSize)
    }

    @Test
    fun deserialize_legacyNotebookTable_defaultsToAutomaticMarker() {
        val legacySql = """
            CREATE TABLE notebooks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            );
            INSERT INTO notebooks (id,name,createdAt) VALUES (2,'Legacy book',100);
            CREATE TABLE notes (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                notebookId INTEGER NOT NULL DEFAULT 1,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            );
        """.trimIndent()

        val notebook = SqlBackupCodec.deserialize(context, legacySql).notebooks.single { it.id == 2L }
        assertEquals(NotebookMarkerType.AUTO.name, notebook.markerType)
        assertEquals(1f, notebook.cropSize)
    }
}
