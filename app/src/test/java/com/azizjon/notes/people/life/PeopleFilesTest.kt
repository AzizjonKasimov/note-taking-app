package com.azizjon.notes.people.life

import com.azizjon.notes.people.data.AffiliationEntity
import com.azizjon.notes.people.data.AiFeedbackEntity
import com.azizjon.notes.people.data.CapabilityEntity
import com.azizjon.notes.people.data.FactEntity
import com.azizjon.notes.people.data.InteractionEntity
import com.azizjon.notes.people.data.NeedEntity
import com.azizjon.notes.people.data.NetworkSnapshot
import com.azizjon.notes.people.data.PeopleDataException
import com.azizjon.notes.people.data.PeoplePayload
import com.azizjon.notes.people.data.PersonEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made-up people only. */
class PeopleFilesTest {
    private val dana = PersonEntity(
        id = 12, name = "Dana Whitfield", location = "Berlin", contact = "dana@example.test",
        relationship = "Met at a design fair", createdAt = 1_000, updatedAt = 2_000, aliases = "Dana W",
    )
    private val minsu = PersonEntity(id = 7, name = "김민수 (Lumen CTO)", createdAt = 3_000, updatedAt = 4_000)
    private val payload = PeoplePayload(
        NetworkSnapshot(
            people = listOf(dana, minsu),
            interactions = listOf(
                InteractionEntity(id = 1, personId = 12, note = "Older talk.", occurredAt = 10, createdAt = 10),
                InteractionEntity(id = 2, personId = 12, note = "Newer talk.", occurredAt = 20, createdAt = 20, origin = InteractionEntity.ORIGIN_ASSISTANT),
                InteractionEntity(id = 3, personId = 7, note = "Coffee.", occurredAt = 30, createdAt = 30),
            ),
            needs = listOf(NeedEntity(id = 4, personId = 12, text = "Needs a bookkeeper.", lastConfirmedAt = 20, createdAt = 20, sourceInteractionId = 2)),
            capabilities = listOf(CapabilityEntity(id = 5, personId = 7, text = "Knows seed investors.", lastConfirmedAt = 30, createdAt = 30)),
            affiliations = listOf(AffiliationEntity(id = 6, personId = 12, organization = "Lumen Labs", role = "Advisor", current = true, lastConfirmedAt = 20, createdAt = 20)),
            facts = listOf(FactEntity(id = 8, personId = 7, text = "Volunteers at a shelter.", lastConfirmedAt = 30, createdAt = 30)),
        ),
        feedback = listOf(AiFeedbackEntity(id = 9, stage = "agent", label = "wrong_target", userMessage = "Met Dana.", assistantMessage = "Saved.", appVersion = "2.5", createdAt = 40)),
    )

    @Test
    fun eachPersonGetsAFileNamedAfterThemAndTheReportsGetTheirs() {
        assertEquals(
            listOf("people/김민수-lumen-cto-7.json", "people/dana-whitfield-12.json", "people/reports.json"),
            PeopleFiles.files(payload).keys.toList(),
        )
        assertEquals("person-3.json", PeopleFiles.fileName(PersonEntity(id = 3, name = "🙂", createdAt = 1, updatedAt = 1)))
    }

    @Test
    fun everythingButContactValuesComesBack() {
        val back = PeopleFiles.read(PeopleFiles.files(payload))

        assertEquals(payload.snapshot.people.map { it.copy(contact = "") }.sortedBy { it.id }, back.snapshot.people)
        assertEquals(payload.snapshot.interactions.sortedBy { it.id }, back.snapshot.interactions.sortedBy { it.id })
        assertEquals(payload.snapshot.needs, back.snapshot.needs)
        assertEquals(payload.snapshot.capabilities, back.snapshot.capabilities)
        assertEquals(payload.snapshot.affiliations, back.snapshot.affiliations)
        assertEquals(payload.snapshot.facts, back.snapshot.facts)
        assertEquals(payload.feedback, back.feedback)
    }

    @Test
    fun noContactValueIsWrittenAndEachRecordIsOneLine() {
        val dana = PeopleFiles.files(payload).getValue("people/dana-whitfield-12.json")

        assertFalse(dana.contains("dana@example.test"))
        assertFalse(JSONObject(dana).getJSONObject("person").has("contact"))
        // One line per record: a change to one note changes one line, so git keeps a small change.
        assertTrue(dana.lines().any { it.startsWith("{\"id\":2") || it.contains("\"note\":\"Newer talk.\"") })
        // Newest note first, as a reader wants them.
        assertTrue(dana.indexOf("Newer talk.") < dana.indexOf("Older talk."))
    }

    @Test
    fun theSameRecordsWriteTheSameFiles() {
        // Saving again after no change must change nothing, or every save would commit every person.
        assertEquals(PeopleFiles.files(payload), PeopleFiles.files(payload.copy()))
    }

    @Test
    fun onlyPeoplesOwnFilesCount() {
        assertTrue(PeopleFiles.isPersonFile("people/dana-whitfield-12.json"))
        assertFalse(PeopleFiles.isPersonFile("people/reports.json"))
        assertFalse(PeopleFiles.isPersonFile("people/drafts/20261128T201530Z-1a2b3c4d.txt"))
        assertFalse(PeopleFiles.isPersonFile("people/README.md"))
        assertFalse(PeopleFiles.isPersonFile("inbox/dana.json"))
    }

    @Test
    fun brokenOrForeignFilesAreRefusedBeforeAnythingIsWritten() {
        val files = PeopleFiles.files(payload)
        // A record pointing at a note that is not there.
        val broken = files + ("people/dana-whitfield-12.json" to files.getValue("people/dana-whitfield-12.json").replace("\"sourceInteractionId\":2", "\"sourceInteractionId\":99"))
        assertThrows(PeopleDataException::class.java) { PeopleFiles.read(broken) }
        assertThrows(PeopleDataException::class.java) { PeopleFiles.read(mapOf("people/x-1.json" to "not json")) }
        assertThrows(PeopleDataException::class.java) { PeopleFiles.read(mapOf("people/x-1.json" to """{"format":"something-else","version":1}""")) }
    }

    @Test
    fun theCommitSaysWhoseFilesChanged() {
        val names = mapOf("people/dana-whitfield-12.json" to "Dana Whitfield", "people/priya-raman-3.json" to "Priya Raman")
        assertEquals(
            "People: Dana Whitfield, Priya Raman",
            PeopleInLife.commitMessage(mapOf("people/dana-whitfield-12.json" to "x", "people/priya-raman-3.json" to "y"), names),
        )
        assertEquals(
            "People: reports, removed tomas-reyes-4",
            PeopleInLife.commitMessage(mapOf("people/tomas-reyes-4.json" to null, "people/reports.json" to "z"), names),
        )
        val many = (1..5).associate { "people/p-$it.json" to "x" }
        assertEquals("People: p-1, p-2, p-3, and 2 more", PeopleInLife.commitMessage(many, emptyMap()))
    }

    @Test
    fun onlyPeopleWhoAreGoneCountAsRemoved() {
        assertEquals(12L, PeopleFiles.idOf("people/dana-whitfield-12.json"))
        assertEquals(7L, PeopleFiles.idOf("people/person-7.json"))
        assertEquals(null, PeopleFiles.idOf("people/notes.json"))
        // Dana was renamed (her old file is stale, but she is still there); Tomas was deleted.
        val stale = setOf("people/dana-whitfield-12.json", "people/tomas-reyes-4.json", "people/notes.json")
        assertEquals(2, PeopleInLife.gone(stale, ids = setOf(12L, 7L)))
        // A new naming rule renames everyone at once, and nobody is gone.
        assertEquals(0, PeopleInLife.gone((1L..40L).map { "people/old-name-$it.json" }.toSet(), ids = (1L..40L).toSet()))
    }

    @Test
    fun aDraftsNameSaysWhenItWasQueued() {
        assertEquals(1_764_360_930_000L, PeopleInLife.queuedAt("20251128T201530Z-1a2b3c4d"))
        assertEquals(null, PeopleInLife.queuedAt("not-a-time"))
    }
}
