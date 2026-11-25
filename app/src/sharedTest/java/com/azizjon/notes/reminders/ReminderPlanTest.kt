package com.azizjon.notes.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/**
 * The reminder bot's own tests (`test_plan.py`), ported one for
 * one, so the app and the bot read and change the list the same way; plus the app's own cases.
 */
class ReminderPlanTest {

    private fun d(text: String): LocalDate = LocalDate.parse(text)

    private fun checklists(vararg texts: String): LinkedHashMap<String, Checklist> {
        val out = LinkedHashMap<String, Checklist>()
        for (text in texts) {
            val c = ReminderPlan.parseChecklist(text, "x")
            out[c.name] = c
        }
        return out
    }

    private fun upcoming(events: String = "", followUps: String = "", questions: String = "") = """# Upcoming

Intro text with an example: - [ ] 2026-01-01 this is not a reminder

## Events

$events

## Follow-ups

$followUps

## Questions

$questions
"""

    @Test
    fun checklistStepsAreSortedAndQuestionsKeptApart() {
        val c = ReminderPlan.parseChecklist(MEETUP, "fallback")
        assertEquals(Triple("meetup", "Meetup", false), Triple(c.name, c.title, c.repeats))
        assertEquals(
            listOf(
                -7 to "Post on Luma",
                -7 to "Make the poster", // same day: file order kept
                -1 to "Pack the HDMI cable",
                0 to "Welcome people at the door",
                1 to "Post the recap",
            ),
            c.steps.filter { !it.ask }.map { it.offset to it.text },
        )
        assertEquals(listOf(3 to true), c.steps.filter { it.ask }.map { it.offset to it.ask })
    }

    @Test
    fun frontmatterCommentIsDroppedButAHashInATitleIsKept() {
        assertEquals("monthly 1st,3rd Sat", ReminderPlan.parseChecklist(TOASTMASTERS, "x").repeat)
        assertEquals("Meetup #17", ReminderPlan.parseChecklist("---\ntitle: Meetup #17\n---\n", "x").title)
    }

    @Test
    fun weeklyRepeat() {
        assertEquals(
            listOf(d("2026-11-26") to "10:30", d("2026-12-03") to "10:30"),
            ReminderPlan.occurrences("weekly Thu 10:30", d("2026-11-23"), d("2026-12-06")),
        )
    }

    @Test
    fun monthlyFirstAndThirdSaturdayMatchesTheClub() {
        // The club met on Sat 7 Nov and Sat 21 Nov 2026; 28 Nov is the 4th Saturday.
        val got = ReminderPlan.occurrences("monthly 1st,3rd Sat", d("2026-11-01"), d("2026-12-31")).map { it.first }
        assertEquals(listOf(d("2026-11-07"), d("2026-11-21"), d("2026-12-05"), d("2026-12-19")), got)
    }

    @Test
    fun yearlyRepeat() {
        assertEquals(
            listOf(d("2027-06-18") to "19:00", d("2028-06-18") to "19:00"),
            ReminderPlan.occurrences("yearly 06-18 19:00", d("2027-06-01"), d("2028-06-30")),
        )
        val leap = ReminderPlan.occurrences("yearly 2-29", d("2027-01-01"), d("2028-12-31")).map { it.first }
        assertEquals(listOf(d("2028-02-29")), leap)
    }

    @Test
    fun badRepeatRuleThrows() {
        for (rule in listOf("fortnightly Sat", "weekly Funday", "monthly first Sat", "yearly Jun 18", "yearly 13-01")) {
            try {
                ReminderPlan.occurrences(rule, d("2026-11-01"), d("2026-11-30"))
                fail("accepted '$rule'")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun upcomingIsReadWithEventsItemsAndMeta() {
        val text = upcoming(
            events = "### 2026-11-28 14:00 · Meetup #17\nchecklist: meetup\nwhere: Hall A\n" +
                "- [ ] 2026-11-24 Get the speaker details\n- [x] 2026-11-20 Put it on Luma\n" +
                "- [ ] 2026-12-01 Ask: When is the next meetup?\n",
            followUps = "- [ ] 2026-11-25 Alex: answer about travel support\n- [-] 2026-11-20 Skipped thing\n",
            questions = "- [ ] 2026-11-24 When is the December night?\n",
        )
        val up = ReminderPlan.parseUpcoming(text)
        val ev = up.events.single()
        assertEquals(
            listOf(d("2026-11-28"), "14:00", "Meetup #17", "meetup", "Hall A"),
            listOf(ev.date, ev.time, ev.title, ev.checklist, ev.meta["where"]),
        )
        assertEquals(
            listOf(' ' to ItemKind.TASK, 'x' to ItemKind.TASK, ' ' to ItemKind.ASK),
            ev.items.map { it.mark to it.kind },
        )
        assertEquals(
            listOf(ItemKind.FOLLOW_UP, ItemKind.FOLLOW_UP, ItemKind.ASK),
            up.items.filter { it.event == null }.map { it.kind },
        )
        assertEquals(6, up.items.size) // the intro's checkbox is not a reminder
    }

    @Test
    fun expandAddsStepsOnceWithDates() {
        val text = upcoming(events = "### 2026-11-28 14:00 · Meetup #17\nchecklist: meetup\nwhere: Hall A\n- [ ] 2026-11-24 A custom item\n")
        val (out, changes) = ReminderPlan.expand(text, checklists(MEETUP), d("2026-11-23"))
        assertEquals(listOf("added 6 steps to 2026-11-28 Meetup #17, 2 of them already past"), changes)
        val ev = ReminderPlan.parseUpcoming(out).events[0]
        assertTrue(ev.hasSteps)
        assertEquals(
            listOf(
                "2026-11-24" to "A custom item",
                "2026-11-21" to "Post on Luma",
                "2026-11-21" to "Make the poster",
                "2026-11-27" to "Pack the HDMI cable",
                "2026-11-28" to "Welcome people at the door",
                "2026-11-29" to "Post the recap",
                "2026-12-01" to "Ask: When is the next meetup? (if no date is set yet)",
            ),
            ev.items.map { it.date.toString() to it.text },
        )
        assertEquals(out to emptyList<String>(), ReminderPlan.expand(out, checklists(MEETUP), d("2026-11-24")))
    }

    @Test
    fun aDatePlaceholderBecomesTheEventDay() {
        val c = ReminderPlan.parseChecklist(
            "---\nname: tm\nrepeat: none\n---\n## What the bot should ask\n- day -14: Are you going on <date>?\n",
            "x",
        )
        assertEquals(listOf("- [ ] 2026-11-21 Ask: Are you going on Sat 5 Dec?"), ReminderPlan.stepLines(d("2026-12-05"), c))
    }

    @Test
    fun expandSkipsCancelledEventsAndWarnsAboutUnknownChecklists() {
        val text = upcoming(
            events = "### 2026-11-28 · Gone\nchecklist: meetup\nstatus: cancelled\n\n### 2026-11-29 · Odd\nchecklist: nosuch\n",
        )
        val (out, changes) = ReminderPlan.expand(text, checklists(MEETUP), d("2026-11-23"))
        assertEquals(listOf("warning: no checklist named 'nosuch' (2026-11-29 Odd)"), changes)
        assertFalse("steps: added" in out)
    }

    @Test
    fun expandAddsRepeatingEventsInDateOrder() {
        val text = upcoming(events = "### 2026-11-28 14:00 · Meetup #17\nchecklist: meetup\nsteps: added 2026-11-20\n")
        val (out, changes) = ReminderPlan.expand(text, checklists(CLASS, TOASTMASTERS), d("2026-11-23"))
        val up = ReminderPlan.parseUpcoming(out)
        // The class looks 14 days ahead; Toastmasters 15, since its first step is on day -14.
        assertEquals(
            listOf(
                "2026-11-26" to "English class",
                "2026-11-28" to "Meetup #17",
                "2026-12-03" to "English class",
                "2026-12-05" to "Toastmasters meeting",
            ),
            up.events.map { it.date.toString() to it.title },
        )
        assertEquals(
            listOf(
                "added English class on 2026-11-26",
                "added English class on 2026-12-03",
                "added Toastmasters meeting on 2026-12-05",
            ),
            changes,
        )
        val cls = up.events.first { it.date == d("2026-11-26") }
        assertEquals("10:30", cls.time)
        assertEquals("the center", cls.meta["where"])
        assertEquals(listOf("2026-11-25", "2026-11-26"), cls.items.map { it.date.toString() })
        assertEquals(out to emptyList<String>(), ReminderPlan.expand(out, checklists(CLASS, TOASTMASTERS), d("2026-11-23")))
    }

    @Test
    fun expandAddsAYearlyEventAsEarlyAsItsFirstStep() {
        val birthday = "---\nname: birthday\ntitle: Birthday\nrepeat: yearly 06-18\n---\n## Steps\n- day -21: Plan the gift\n- day 0: Celebrate\n"
        val (out, changes) = ReminderPlan.expand(upcoming(), checklists(birthday), d("2027-05-27"))
        assertEquals(listOf("added Birthday on 2027-06-18"), changes)
        val ev = ReminderPlan.parseUpcoming(out).events.single()
        assertEquals(
            listOf("2027-05-28" to "Plan the gift", "2027-06-18" to "Celebrate"),
            ev.items.map { it.date.toString() to it.text },
        )
        val (early, none) = ReminderPlan.expand(upcoming(), checklists(birthday), d("2027-05-26")) // 23 days ahead: not yet
        assertEquals(emptyList<String>(), none)
        assertTrue(ReminderPlan.parseUpcoming(early).events.isEmpty())
    }

    @Test
    fun aCancelledRepeatingEventIsNotAddedAgain() {
        val text = upcoming(events = "### 2026-11-26 10:30 · English class\nchecklist: english-class\nstatus: cancelled\n")
        val (_, changes) = ReminderPlan.expand(text, checklists(CLASS), d("2026-11-23"))
        assertEquals(listOf("added English class on 2026-12-03"), changes)
    }

    @Test
    fun pruneMovesOldEventsAndOldFinishedItems() {
        val text = upcoming(
            events = "### 2026-11-01 · Old\n- [ ] 2026-10-31 Never done\n\n### 2026-11-20 · Recent\n- [x] 2026-11-19 Done\n",
            followUps = "- [x] 2026-11-01 Old and done\n- [ ] 2026-11-01 Old but open\n",
        )
        val pruned = ReminderPlan.prune(text, d("2026-11-23"))
        assertEquals(listOf("Recent"), ReminderPlan.parseUpcoming(pruned.text).events.map { it.title })
        assertTrue("Old but open" in pruned.text && "Old and done" !in pruned.text)
        assertEquals("### 2026-11-01 · Old", pruned.moved[0])
        assertTrue("- [x] 2026-11-01 Old and done" in pruned.moved)
        assertEquals(2, pruned.changes.size)
    }

    @Test
    fun morningMessage() {
        val text = upcoming(
            events = "### 2026-11-24 18:30 · Demo night\nwhere: Gangnam\n" +
                "- [ ] 2026-11-23 Import new Luma sign-ups\n" +
                "- [ ] 2026-11-24 Bring the timing cards\n\n" +
                "### 2026-11-28 14:00 · Meetup #17\n" +
                "- [ ] 2026-11-21 Send the team the poster\n" +
                "- [ ] 2026-11-10 Something long forgotten\n" +
                "- [x] 2026-11-23 Already done\n" +
                "- [ ] 2026-11-23 Ask: Who holds the timing cards?\n\n" +
                "### 2026-11-26 10:30 · Cancelled class\nstatus: cancelled\n- [ ] 2026-11-23 Should not show\n\n" +
                "### 2026-12-10 · Far away\n",
            followUps = "- [ ] 2026-11-23 Alex: answer about travel support\n",
            questions = "- [ ] 2026-11-30 Not yet\n",
        )
        val msg = ReminderPlan.morningMessage(
            ReminderPlan.parseUpcoming(text),
            d("2026-11-23"),
            listOf("Replies are not being processed."),
        )
        assertEquals(
            listOf(
                "Good morning! Mon 23 Nov",
                "",
                "Today",
                "1. Demo night: Import new Luma sign-ups",
                "2. Alex: answer about travel support",
                "",
                "Still open",
                "3. Meetup #17: Send the team the poster (due Sat)",
                "+1 older, still open in the list",
                "",
                "Questions",
                "4. Meetup #17: Who holds the timing cards?",
                "",
                "Coming up",
                "Tue 24 Nov 18:30 Demo night",
                "Sat 28 Nov 14:00 Meetup #17",
                "",
                "⚠️ Replies are not being processed.",
                "",
                ReminderPlan.REPLY_HINT,
            ).joinToString("\n"),
            msg,
        )
    }

    @Test
    fun morningMessageOnAnEmptyDayAndTheEventDay() {
        assertEquals(
            "Good morning! Mon 23 Nov\n\nNothing due today.",
            ReminderPlan.morningMessage(ReminderPlan.parseUpcoming(upcoming()), d("2026-11-23")),
        )
        val text = upcoming(events = "### 2026-11-24 18:30 · Demo night\nwhere: Gangnam\n- [ ] 2026-11-24 Bring the timing cards\n")
        val msg = ReminderPlan.morningMessage(ReminderPlan.parseUpcoming(text), d("2026-11-24"))
        assertTrue("Today\n📅 18:30 Demo night · Gangnam\n1. Demo night: Bring the timing cards" in msg)
    }

    @Test
    fun eveningMessage() {
        val text = upcoming(
            events = "### 2026-11-28 14:00 · Meetup #17\nwhere: Hall A, 4th floor\n" +
                "- [ ] 2026-11-25 Check that the venue told the guests the way\n" +
                "- [ ] 2026-11-27 Pack the HDMI cable\n" +
                "- [ ] 2026-11-28 Welcome people at the door\n" +
                "- [x] 2026-11-27 Print the timing cards\n" +
                "- [ ] 2026-11-29 Post the recap\n" +
                "- [ ] 2026-11-28 Ask: Who holds the timing cards?\n",
        )
        val up = ReminderPlan.parseUpcoming(text)
        assertNull(ReminderPlan.eveningMessage(up, d("2026-11-26")))
        assertEquals(
            listOf(
                "Tomorrow: Meetup #17, Sat 28 Nov 14:00",
                "Where: Hall A, 4th floor",
                "",
                "Tonight and tomorrow",
                "1. Pack the HDMI cable",
                "2. Welcome people at the door",
                "",
                "Still open for it",
                "3. Check that the venue told the guests the way (due Wed)",
                "",
                "Questions",
                "4. Who holds the timing cards?",
                "",
                ReminderPlan.REPLY_HINT,
            ).joinToString("\n"),
            ReminderPlan.eveningMessage(up, d("2026-11-27")),
        )
    }

    // ---- The app's own cases ----

    @Test
    fun linesSplitAsPythonDoes() {
        assertEquals(emptyList<String>(), ReminderPlan.splitLines(""))
        assertEquals(listOf("a", "b"), ReminderPlan.splitLines("a\nb\n"))
        assertEquals(listOf("a", "", "b"), ReminderPlan.splitLines("a\r\n\r\nb"))
    }

    @Test
    fun pastStartsWithItsHeaderOnce() {
        val first = ReminderPlan.appendToPast(null, listOf("### 2026-11-01 · Old", "- [ ] 2026-10-31 Never done"))
        assertEquals(ReminderPlan.PAST_HEADER + "\n### 2026-11-01 · Old\n- [ ] 2026-10-31 Never done\n", first)
        val second = ReminderPlan.appendToPast(first, listOf("- [x] 2026-11-02 Done"))
        assertEquals(first + "\n- [x] 2026-11-02 Done\n", second)
    }

    private companion object {
        val MEETUP = """---
name: meetup
title: Meetup
repeat: none
---

# Meetup checklist

## Steps

- day -7: Post on Luma
- day -1: Pack the HDMI cable
- day -7: Make the poster
- day 0: Welcome people at the door
- day +1: Post the recap

## People to keep in the loop

- day -3: this line is not a step, it is in another section

## What the bot should ask

- day +3: When is the next meetup? (if no date is set yet)
"""

        val CLASS = """---
name: english-class
title: English class
repeat: weekly Thu 10:30
where: the center
---
## Steps
- day -1: Make the plan and the cue card
- day 0: Teach the class
"""

        val TOASTMASTERS = """---
name: toastmasters
title: Toastmasters meeting
repeat: monthly 1st,3rd Sat   # the club's rule
---
## Steps
- day -14: If speaking: write the script
- day 0: Go to the meeting
"""
    }
}
