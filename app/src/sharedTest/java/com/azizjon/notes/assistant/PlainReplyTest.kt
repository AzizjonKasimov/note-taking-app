package com.azizjon.notes.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs on the PC and on a device: Android's regex engine differs from the PC's. */
class PlainReplyTest {
    @Test
    fun takesOutMarkdown() {
        assertEquals("Saved it in ideas/projects.md.", PlainReply.from("**Saved** it in `ideas/projects.md`."))
        assertEquals("See the list.", PlainReply.from("See [the list](https://example.com/a)."))
        assertEquals("Done", PlainReply.from("## Done"))
        assertEquals("• one\n• two", PlainReply.from("- one\n* two"))
        assertEquals("an important note", PlainReply.from("an *important* note"))
    }

    @Test
    fun leavesPlainTextAlone() {
        val text = "Added a reminder for Fri 28 Nov to call Adam (2 * 3 = 6)."
        assertEquals(text, PlainReply.from(text))
    }

    @Test
    fun keepsOnlyTheListsWritingRules() {
        val readme = "# R\n\nIntro.\n\n## How `upcoming.md` is written\n\nA.\n\n## Other\n\nB.\n\n## Rules for the bot\n\nC.\n"
        assertEquals("## How `upcoming.md` is written\n\nA.\n\n## Rules for the bot\n\nC.", AssistantPrompt.listSections(readme))
    }
}
