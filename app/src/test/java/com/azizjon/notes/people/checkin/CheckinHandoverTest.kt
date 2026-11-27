package com.azizjon.notes.people.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckinHandoverTest {
    private val rows = listOf(
        CheckinEntity("contact:3141r2-2A4E", "contacts", "Ana Lee", 1, 2, CheckinEntity.STATUS_NEVER, 3),
        CheckinEntity("chat:telegram:ana lee", "telegram", "Ana Lee", 4, 5, CheckinEntity.STATUS_LATER, 6),
        CheckinEntity("need:7", CheckinEntity.SOURCE_RECORDS, "", 8, 9, CheckinEntity.STATUS_NEVER, 10),
        CheckinEntity("chat:kakaotalk:김민수", "kakaotalk", "김민수", 11, 12, CheckinEntity.STATUS_DONE, 13),
    )

    @Test
    fun everyAnswerSurvivesTheHandOver() {
        assertEquals(rows, CheckinHandover.decode(CheckinHandover.encode(rows)))
    }

    @Test
    fun anythingButNetworkAppsFileIsRefused() {
        listOf(
            "not json",
            """{"format":"something-else","version":1,"rows":[]}""",
            """{"format":"network-app-checkins","version":2,"rows":[]}""",
            """{"format":"network-app-checkins","version":1}""",
            """{"format":"network-app-checkins","version":1,"rows":["not a row"]}""",
            """{"format":"network-app-checkins","version":1,"rows":[{"ref":"contact:a","status":"maybe"}]}""",
            """{"format":"network-app-checkins","version":1,"rows":[{"ref":" ","status":"never"}]}""",
        ).forEach { text ->
            assertThrows(text, IllegalArgumentException::class.java) { CheckinHandover.decode(text) }
        }
    }

    @Test
    fun networkAppsReleaseKeyIsAlwaysTrusted() {
        assertTrue("34ef3571c61eabf4cfa859fb627a2de200140f79a10fbe6bd8151c254d067888" in CheckinHandover.networkAppCerts)
    }
}
