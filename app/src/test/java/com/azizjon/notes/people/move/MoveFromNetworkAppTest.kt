package com.azizjon.notes.people.move

import com.azizjon.notes.people.checkin.CheckinEntity
import com.azizjon.notes.people.data.InteractionEntity
import com.azizjon.notes.people.data.PeopleDataException
import com.azizjon.notes.people.data.PeopleJson
import com.azizjon.notes.people.data.PersonEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made-up people only. */
class MoveFromNetworkAppTest {
    private val ana = PersonEntity(id = 1, name = "Ana Lee", contact = "+82 10-0000-0000", createdAt = 1, updatedAt = 2)
    private val note = InteractionEntity(id = 2, personId = 1, note = "Met at the fair.", occurredAt = 3, createdAt = 3)
    private val answers = listOf(
        CheckinEntity("contact:3141r2-2A4E", "contacts", "Ana Lee", 1, 2, CheckinEntity.STATUS_NEVER, 3),
        CheckinEntity("chat:kakaotalk:김민수", "kakaotalk", "김민수", 4, 5, CheckinEntity.STATUS_LATER, 6),
    )

    /** The records the way Network App 0.21 writes them: its backup's content before encryption. */
    private fun networkAppPayload(schemaVersion: Int = PeopleJson.SCHEMA_VERSION) = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("exportedAt", 9)
        .put("people", JSONArray().put(PeopleJson.person(ana, withContact = true)))
        .put("interactions", JSONArray().put(PeopleJson.interaction(note)))
        .put("needs", JSONArray())
        .put("capabilities", JSONArray())
        .put("affiliations", JSONArray())
        .put("facts", JSONArray())
        .put("feedback", JSONArray())

    @Test
    fun peopleWithTheirContactValuesAndEveryAnswerCome() {
        val move = MoveFromNetworkApp.decode(MoveFromNetworkApp.encode(networkAppPayload(), answers))

        assertEquals(listOf(ana), move.payload.snapshot.people)
        assertEquals("+82 10-0000-0000", move.payload.snapshot.people.single().contact)
        assertEquals(listOf(note), move.payload.snapshot.interactions)
        assertEquals(answers, move.checkins)
    }

    @Test
    fun anythingButNetworkAppsMoveIsRefused() {
        listOf(
            "not json",
            """{"format":"something-else","version":1,"payload":{}}""",
            """{"format":"network-app-move","version":2,"payload":{}}""",
            """{"format":"network-app-move","version":1}""",
            """{"format":"network-app-move","version":1,"payload":{"schemaVersion":10},"checkins":[]}""",
        ).forEach { text ->
            val error = runCatching { MoveFromNetworkApp.decode(text) }.exceptionOrNull()
            assertTrue("$text should be refused", error is IllegalArgumentException || error is PeopleDataException || error is org.json.JSONException)
        }
        assertThrows(PeopleDataException::class.java) {
            MoveFromNetworkApp.decode(MoveFromNetworkApp.encode(networkAppPayload(schemaVersion = 9), answers))
        }
        val badAnswer = MoveFromNetworkApp.encode(networkAppPayload(), emptyList())
            .replace("\"checkins\":[]", "\"checkins\":[{\"ref\":\"contact:a\",\"status\":\"maybe\"}]")
        assertThrows(IllegalArgumentException::class.java) { MoveFromNetworkApp.decode(badAnswer) }
    }

    @Test
    fun networkAppsReleaseKeyIsAlwaysTrusted() {
        assertTrue("34ef3571c61eabf4cfa859fb627a2de200140f79a10fbe6bd8151c254d067888" in MoveFromNetworkApp.networkAppCerts)
    }
}
