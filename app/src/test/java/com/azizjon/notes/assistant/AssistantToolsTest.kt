package com.azizjon.notes.assistant

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantToolsTest {
    private val list = """
        # Upcoming

        Intro.

        ## Events

        ### 2026-11-28 14:00 · Meetup #17
        checklist: meetup
        steps: added 2026-11-23
        - [ ] 2026-11-27 Print the name tags

        ## Follow-ups

        - [ ] 2026-11-30 Sam: send the photos

        ## Questions

    """.trimIndent() + "\n"

    private fun repo() = FolderRepo.create(
        mapOf(
            "README.md" to "# Notes\n",
            "ideas/README.md" to "# Ideas\n",
            "ideas/projects.md" to "# Project ideas\n\n1. A fridge app.\n",
            "reminders/upcoming.md" to list,
            "places/cafes.md" to "# Cafes\n\n- Blue Door: good flat white\n- Blue Door: second visit\n",
        ),
    )

    private fun run(tools: AssistantTools, log: TurnLog, name: String, vararg args: Pair<String, Any>): ToolResult = runBlocking {
        tools.execute(name, JSONObject().apply { args.forEach { (k, v) -> put(k, v) } }, log)
    }

    @Test
    fun listsAFolderWithoutGitsOwnFiles() {
        val tools = AssistantTools(repo())
        val top = run(tools, TurnLog(), "list_folder", "path" to "")
        assertFalse(top.isError)
        assertTrue(top.output, top.output.contains("- ideas/"))
        assertTrue(top.output.contains("- README.md (1 line)"))
        assertFalse(top.output.contains(".git"))
        val ideas = run(tools, TurnLog(), "list_folder", "path" to "ideas/")
        assertTrue(ideas.output, ideas.output.contains("- projects.md (3 lines)"))
        assertTrue(run(tools, TurnLog(), "list_folder", "path" to "nope").isError)
        assertTrue(run(tools, TurnLog(), "list_folder", "path" to "../outside").isError)
        assertTrue(run(tools, TurnLog(), "list_folder", "path" to ".git").isError)
    }

    @Test
    fun readsWholeFilesAndPartsOfLongOnes() {
        val repo = repo()
        val tools = AssistantTools(repo)
        assertEquals("# Project ideas\n\n1. A fridge app.", run(tools, TurnLog(), "read_file", "path" to "ideas/projects.md").output)
        val part = run(tools, TurnLog(), "read_file", "path" to "reminders/upcoming.md", "from_line" to 5, "max_lines" to 2)
        assertEquals("[lines 5-6 of 17]\n## Events\n\n[11 more lines: read on with from_line=7]", part.output)
        assertTrue(run(tools, TurnLog(), "read_file", "path" to "ideas/none.md").isError)
        assertTrue(run(tools, TurnLog(), "read_file", "path" to "/etc/passwd").isError)
        assertTrue(run(tools, TurnLog(), "read_file", "path" to ".git/config").isError)
    }

    @Test
    fun searchFindsNamesAndLinesIgnoringCase() {
        val tools = AssistantTools(repo())
        val hits = run(tools, TurnLog(), "search", "query" to "blue door").output
        assertEquals("places/cafes.md:3: - Blue Door: good flat white\nplaces/cafes.md:4: - Blue Door: second visit", hits)
        assertTrue(run(tools, TurnLog(), "search", "query" to "IDEAS").output.contains("ideas/projects.md (its name)"))
        assertTrue(run(tools, TurnLog(), "search", "query" to "flat white", "folder" to "ideas").output.startsWith("Nothing found"))
        assertTrue(run(tools, TurnLog(), "search", "query" to "a").isError)
    }

    @Test
    fun eachChangeIsACommitOfItsOwnWithOnlyItsFile() {
        val repo = repo()
        repo.files.write("places/cafes.md", "# Cafes\n\nAn edit the user has not saved yet.\n")
        val tools = AssistantTools(repo)
        val log = TurnLog()
        val result = run(
            tools, log, "add_text",
            "path" to "ideas/projects.md", "text" to "2. A bus app that knows the weather.", "change" to "Added the bus app idea",
        )
        assertEquals("Saved in ideas/projects.md.", result.output)
        assertEquals("# Project ideas\n\n1. A fridge app.\n2. A bus app that knows the weather.\n", repo.text("ideas/projects.md"))
        assertEquals("Assistant: Added the bus app idea", repo.log().first())
        // The unsaved edit elsewhere stays out of the commit.
        assertEquals(setOf("places/cafes.md"), repo.uncommitted())
        val change = log.changes.single()
        assertEquals("Added the bus app idea", change.summary)
        assertEquals(FileChange("ideas/projects.md", "# Project ideas\n\n1. A fridge app.\n", repo.text("ideas/projects.md")), change.files.single())
    }

    @Test
    fun replaceTextNeedsTheOldTextExactlyOnce() {
        val repo = repo()
        val tools = AssistantTools(repo)
        val log = TurnLog()
        val missing = run(tools, log, "replace_text", "path" to "places/cafes.md", "old_text" to "Red Door", "new_text" to "x", "change" to "c")
        assertTrue(missing.isError)
        val twice = run(tools, log, "replace_text", "path" to "places/cafes.md", "old_text" to "Blue Door", "new_text" to "x", "change" to "c")
        assertTrue(twice.output, twice.isError && twice.output.contains("2 times"))
        val once = run(
            tools, log, "replace_text",
            "path" to "places/cafes.md", "old_text" to "Blue Door: second visit", "new_text" to "Blue Door: closed on Mondays", "change" to "Noted",
        )
        assertFalse(once.isError)
        assertTrue(repo.text("places/cafes.md")!!.contains("Blue Door: closed on Mondays"))
        assertEquals(1, log.changes.size)
        assertEquals(2, repo.log().size)
    }

    @Test
    fun addTextAfterALine() {
        val repo = repo()
        val tools = AssistantTools(repo)
        val ok = run(
            tools, TurnLog(), "add_text",
            "path" to "reminders/upcoming.md", "after_line" to "## Follow-ups", "text" to "\n- [ ] 2026-11-30 Call the bank about the card",
            "change" to "Added a reminder to call the bank",
        )
        assertFalse(ok.output, ok.isError)
        assertTrue(repo.text("reminders/upcoming.md")!!.contains("## Follow-ups\n\n- [ ] 2026-11-30 Call the bank about the card\n"))
        assertTrue(run(tools, TurnLog(), "add_text", "path" to "reminders/upcoming.md", "after_line" to "## Nope", "text" to "x", "change" to "c").isError)
    }

    @Test
    fun theReminderListTakesOnlyLinesItCanRead() {
        val repo = repo()
        val tools = AssistantTools(repo)
        val log = TurnLog()
        val undated = run(
            tools, log, "add_text",
            "path" to "reminders/upcoming.md", "after_line" to "## Follow-ups", "text" to "- [ ] Friday Call the bank", "change" to "c",
        )
        assertTrue(undated.output, undated.isError && undated.output.contains("- [ ] Friday Call the bank"))
        assertEquals(list, repo.text("reminders/upcoming.md"))
        assertTrue(log.changes.isEmpty())
        val event = run(
            tools, log, "add_text",
            "path" to "reminders/upcoming.md", "after_line" to "- [ ] 2026-11-27 Print the name tags",
            "text" to "\n### 2026-12-20 14:00 · Meetup #18\nchecklist: meetup", "change" to "Added Meetup #18 on Sat 20 Dec",
        )
        assertFalse(event.output, event.isError)
    }

    @Test
    fun createFileOnlyInsideAnExistingFolder() {
        val repo = repo()
        val tools = AssistantTools(repo)
        val log = TurnLog()
        assertFalse(run(tools, log, "create_file", "path" to "ideas/bus.md", "text" to "# Bus app", "change" to "Started a note on the bus app").isError)
        assertEquals("# Bus app\n", repo.text("ideas/bus.md"))
        assertFalse(run(tools, log, "create_file", "path" to "ideas/apps/new.md", "text" to "# New", "change" to "c").isError)
        assertTrue(run(tools, log, "create_file", "path" to "ideas/bus.md", "text" to "# Again", "change" to "c").isError)
        assertTrue(run(tools, log, "create_file", "path" to "top.md", "text" to "# Top", "change" to "c").isError)
        assertTrue(run(tools, log, "create_file", "path" to "newtopic/README.md", "text" to "# New", "change" to "c").isError)
        assertTrue(run(tools, log, "create_file", "path" to "ideas/script.py", "text" to "print(1)", "change" to "c").isError)
        assertTrue(run(tools, log, "create_file", "path" to "ideas/.hidden.md", "text" to "# x", "change" to "c").isError)
        assertEquals(2, log.changes.size)
        assertNull(log.changes.first().files.single().before)
    }

    @Test
    fun aFileHoldingAClashIsLeftAlone() {
        val repo = repo()
        repo.files.write("ideas/projects.md", "# Ideas\n<<<<<<< phone\na\n=======\nb\n>>>>>>> PC\n")
        val result = run(AssistantTools(repo), TurnLog(), "add_text", "path" to "ideas/projects.md", "text" to "more", "change" to "c")
        assertTrue(result.isError && result.output.contains("two versions"))
    }

    @Test
    fun everyToolHasASchemaTheGatewayTakes() {
        val defs = AssistantTools(repo()).definitions()
        val names = (0 until defs.length()).map { defs.getJSONObject(it).getString("name") }
        assertEquals(listOf("list_folder", "read_file", "search", "replace_text", "add_text", "create_file"), names)
        for (i in 0 until defs.length()) {
            val def = defs.getJSONObject(i)
            assertTrue(def.getString("name").matches(Regex("^[A-Za-z0-9_]{1,64}$")))
            assertTrue(def.getString("description").length in 1..4000)
            assertEquals("object", def.getJSONObject("input_schema").getString("type"))
        }
    }
}
