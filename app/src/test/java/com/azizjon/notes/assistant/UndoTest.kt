package com.azizjon.notes.assistant

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UndoTest {
    private val before = "# Ideas\n\n1. Fridge app.\n2. Bus app.\n"

    @Test
    fun aChangeNothingTouchedSinceGoesBackToTheTextBefore() {
        val change = AssistantChange("Added", listOf(FileChange("ideas.md", before, "${before}3. Tram app.\n")))
        assertEquals(mapOf("ideas.md" to before), Undo.revert(change) { "${before}3. Tram app.\n" })
    }

    @Test
    fun laterEditsToOtherLinesStay() {
        val after = "${before}3. Tram app.\n"
        val change = AssistantChange("Added", listOf(FileChange("ideas.md", before, after)))
        val now = after.replace("1. Fridge app.", "1. Fridge app, with a camera.")
        assertEquals(mapOf("ideas.md" to before.replace("1. Fridge app.", "1. Fridge app, with a camera.")), Undo.revert(change) { now })
    }

    @Test
    fun aLaterEditOfTheSameLinesMeansAPersonDecides() {
        val after = "${before}3. Tram app.\n"
        val change = AssistantChange("Added", listOf(FileChange("ideas.md", before, after)))
        assertNull(Undo.revert(change) { after.replace("3. Tram app.", "3. Tram app for Daejeon.") })
    }

    @Test
    fun aFileTheAssistantMadeIsDeletedAgainUnlessItChanged() {
        val change = AssistantChange("Made", listOf(FileChange("inbox/a.md", null, "# A\n")))
        assertEquals(mapOf<String, String?>("inbox/a.md" to null), Undo.revert(change) { "# A\n" })
        assertNull(Undo.revert(change) { "# A\nmore\n" })
    }

    @Test
    fun undoingAMadeFileCommitsItsDeletion() = runBlocking {
        val repo = FolderRepo.create(mapOf("inbox/README.md" to "# Inbox\n"))
        val log = TurnLog()
        AssistantTools(repo).execute(
            "create_file",
            JSONObject().put("path", "inbox/call-the-bank.md").put("text", "# Call the bank").put("change", "Saved a note"),
            log,
        )
        val change = log.changes.single()
        repo.commitEdit { read -> Undo.revert(change, read)?.let { com.azizjon.notes.life.FileEdit("Assistant: undo: Saved a note", it) } }
        assertNull(repo.text("inbox/call-the-bank.md"))
        assertEquals(listOf("Assistant: undo: Saved a note", "Assistant: Saved a note", "Start"), repo.log())
        assertEquals(emptySet<String>(), repo.uncommitted())
    }
}
