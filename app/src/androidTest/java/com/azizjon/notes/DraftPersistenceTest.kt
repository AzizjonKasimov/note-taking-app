package com.azizjon.notes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.azizjon.notes.data.ActiveEditorSession
import com.azizjon.notes.data.ActiveEditorSessionStore
import com.azizjon.notes.data.DEFAULT_NOTEBOOK_ID
import com.azizjon.notes.data.DEFAULT_NOTEBOOK_NAME
import com.azizjon.notes.data.EditorSnapshot
import com.azizjon.notes.data.Note
import com.azizjon.notes.data.Notebook
import com.azizjon.notes.data.NotesDatabase
import com.azizjon.notes.data.NotesRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraftPersistenceTest {
    private lateinit var database: NotesDatabase
    private lateinit var repository: NotesRepository
    private lateinit var sessionStore: ActiveEditorSessionStore

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java).build()
        database.notebookDao().upsert(
            Notebook(id = DEFAULT_NOTEBOOK_ID, name = DEFAULT_NOTEBOOK_NAME),
        )
        repository = NotesRepository(database)
        sessionStore = ActiveEditorSessionStore(context).also { it.clear() }
    }

    @After
    fun tearDown() {
        sessionStore.clear()
        database.close()
    }

    @Test
    fun newDraftGetsOneStableIdAcrossHundredsOfSaves() = runBlocking {
        val draft = repository.createDraft(DEFAULT_NOTEBOOK_ID)
        repeat(300) { revision ->
            repository.saveDraft(
                EditorSnapshot(
                    noteId = draft.id,
                    title = "Title $revision",
                    content = "Body $revision",
                    notebookId = DEFAULT_NOTEBOOK_ID,
                ),
            )
        }

        val rows = repository.allNotes()
        assertEquals(1, rows.size)
        assertEquals(draft.id, rows.single().id)
        assertEquals("Body 299", rows.single().content)
    }

    @Test
    fun emptyNewDraftIsRemoved_butClearedExistingNoteIsRetained() = runBlocking {
        val newDraft = repository.createDraft(DEFAULT_NOTEBOOK_ID)
        repository.discardEmptyDraft(newDraft.id)
        assertNull(repository.getById(newDraft.id))

        val existingId = repository.save(Note(title = "Before", content = "Before"))
        repository.saveDraft(EditorSnapshot(existingId, "", "", DEFAULT_NOTEBOOK_ID))
        assertEquals(existingId, repository.getById(existingId)?.id)
    }

    @Test
    fun activeSessionRoundTripsAndClearsSynchronously() {
        val session = ActiveEditorSession(noteId = 42L, isNew = true)
        assertEquals(true, sessionStore.write(session))
        assertEquals(session, sessionStore.read())
        assertEquals(true, sessionStore.clear())
        assertNull(sessionStore.read())
    }
}
