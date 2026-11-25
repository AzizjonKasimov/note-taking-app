package com.azizjon.notes.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ReminderEditsTest {

    private val list = """# Upcoming

## Events

### 2026-11-28 14:00 · Meetup #17
- [ ] 2026-11-27 Pack the HDMI cable
- [x] 2026-11-27 Print the timing cards

## Follow-ups

- [ ] Call Sam
"""

    private fun refOf(text: String, contains: String): ItemRef {
        val up = ReminderPlan.parseUpcoming(text)
        return ReminderEdits.ref(up, up.items.first { contains in it.text })
    }

    @Test
    fun tickingChangesOnlyThatBox() {
        val ref = refOf(list, "HDMI")
        val done = ReminderEdits.setMark(list, ref, 'x')!!
        assertEquals(list.replace("- [ ] 2026-11-27 Pack", "- [x] 2026-11-27 Pack"), done)
        val skipped = ReminderEdits.setMark(list, ref, '-')!!
        assertEquals(list.replace("- [ ] 2026-11-27 Pack", "- [-] 2026-11-27 Pack"), skipped)
    }

    @Test
    fun theItemIsFoundAfterLinesAboveItChanged() {
        val ref = refOf(list, "HDMI")
        val moved = list.replace("## Events\n", "## Events\n\nA new line from the PC.\n")
        val done = ReminderEdits.setMark(moved, ref, 'x')!!
        assertEquals(moved.replace("- [ ] 2026-11-27 Pack", "- [x] 2026-11-27 Pack"), done)
    }

    @Test
    fun anItemChangedElsewhereOrTwiceInTheListIsLeftAlone() {
        val ref = refOf(list, "HDMI")
        assertNull(ReminderEdits.setMark(list.replace("HDMI cable", "HDMI cable and hub"), ref, 'x'))
        val twice = list.replace("## Follow-ups", "- [ ] 2026-11-27 Pack the HDMI cable\n\n## Follow-ups")
        assertNull(ReminderEdits.setMark(twice.replace("### 2026-11-28 14:00 · Meetup #17\n", "### 2026-11-28 14:00 · Meetup #17\nwhere: Hall A\n"), ref, 'x'))
    }

    @Test
    fun movingReplacesTheDateOrAddsOne() {
        val day = LocalDate.parse("2026-11-29")
        assertEquals(
            list.replace("- [ ] 2026-11-27 Pack", "- [ ] 2026-11-29 Pack"),
            ReminderEdits.move(list, refOf(list, "HDMI"), day),
        )
        assertEquals(
            list.replace("- [ ] Call Sam", "- [ ] 2026-11-29 Call Sam"),
            ReminderEdits.move(list, refOf(list, "Sam"), day),
        )
    }

    @Test
    fun windowsLineEndingsSurvive() {
        val crlf = list.replace("\n", "\r\n")
        val done = ReminderEdits.setMark(crlf, refOf(crlf, "HDMI"), 'x')!!
        assertEquals(crlf.replace("- [ ] 2026-11-27 Pack", "- [x] 2026-11-27 Pack"), done)
    }

    @Test
    fun answersGoToTheInboxOnALineEach() {
        val entry = ReminderEdits.inboxEntry(
            LocalDateTime.parse("2026-11-25T10:12"),
            "Meetup #17: When is the next meetup?",
            "  20 December,\n2 PM ",
        )
        assertEquals("- 2026-11-25 10:12 (answered in the Notes app) \"Meetup #17: When is the next meetup?\" 20 December, 2 PM", entry)
        val intro = "# Inbox\n\nThings for the next Claude session.\n"
        val first = ReminderEdits.appendToInbox(intro, entry)
        assertEquals("$intro\n$entry\n", first)
        assertEquals("$intro\n$entry\n$entry\n", ReminderEdits.appendToInbox(first, entry))
        assertEquals("# Inbox\n\n$entry\n", ReminderEdits.appendToInbox(null, entry))
    }
}
