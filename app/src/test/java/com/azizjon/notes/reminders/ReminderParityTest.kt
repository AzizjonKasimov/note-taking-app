package com.azizjon.notes.reminders

import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Checks the port against the reminder bot's `plan.py` on a real list: the same upkeep, day after
 * day, must give the same list, the same lines for past.md, the same changes, and the same morning
 * and evening reminders. The expected results come from the bot's `parity.py`; run with
 * `gradlew testDebugUnitTest --tests *ReminderParityTest -Preminders.parity=<its output folder>`.
 * Without that property the test is skipped.
 */
class ReminderParityTest {

    @Test
    fun theAppMakesWhatTheBotMakes() {
        val root = System.getProperty("reminders.parity")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("set -Preminders.parity=<folder> to compare with plan.py", root != null)
        root!!
        val checklists = ReminderPlan.checklists(
            File(root, "checklists").listFiles { f -> f.name.endsWith(".md") }.orEmpty()
                .associate { it.name to it.readText() },
        )
        var text = File(root, "upcoming.md").readText()
        val days = File(root, "days").listFiles().orEmpty().sortedBy { it.name }
        val problems = ArrayList<String>()
        for (folder in days) {
            val day = LocalDate.parse(folder.name)
            val (expanded, changes) = ReminderPlan.expand(text, checklists, day)
            val pruned = ReminderPlan.prune(expanded, day)
            val allChanges = changes + pruned.changes
            val warnings = allChanges.filter { it.startsWith("warning:") }.map { it.removePrefix("warning: ") }
            val up = ReminderPlan.parseUpcoming(pruned.text)
            fun check(name: String, got: String?) {
                val file = File(folder, name)
                val want = if (file.exists()) file.readText() else null
                if (got != want) problems += "${folder.name} $name: ${firstDifference(want, got)}"
            }
            check("expanded.md", pruned.text)
            check("moved.txt", pruned.moved.joinToString("\n"))
            check("changes.txt", allChanges.joinToString("\n"))
            check("morning.txt", ReminderPlan.morningMessage(up, day, warnings))
            check("evening.txt", ReminderPlan.eveningMessage(up, day))
            text = pruned.text
        }
        assumeTrue("no days in $root", days.isNotEmpty())
        if (problems.isNotEmpty()) fail("${problems.size} differences in ${days.size} days:\n" + problems.take(20).joinToString("\n"))
        println("Same as plan.py on all ${days.size} days (${days.first().name} to ${days.last().name}).")
    }

    private fun firstDifference(want: String?, got: String?): String {
        if (want == null || got == null) return "expected ${if (want == null) "no file" else "a file"}, got ${if (got == null) "none" else "text"}"
        val a = want.split("\n")
        val b = got.split("\n")
        val i = (0 until maxOf(a.size, b.size)).first { a.getOrNull(it) != b.getOrNull(it) }
        return "line ${i + 1}: expected <${a.getOrNull(i)}> got <${b.getOrNull(i)}>"
    }
}
