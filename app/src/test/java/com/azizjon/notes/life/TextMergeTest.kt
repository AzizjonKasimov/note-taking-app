package com.azizjon.notes.life

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMergeTest {

    @Test
    fun changesToDifferentLinesAreCombined() {
        val base = "one\ntwo\nthree\nfour\n"
        val phone = "one\ntwo (phone)\nthree\nfour\n"
        val pc = "one\ntwo\nthree\nfour (pc)\n"
        val result = TextMerge.merge(base, phone, pc)
        assertFalse(result.conflicted)
        assertEquals("one\ntwo (phone)\nthree\nfour (pc)\n", result.text)
    }

    @Test
    fun theSameLineChangedOnBothSidesKeepsBoth() {
        val result = TextMerge.merge("a\nb\nc\n", "a\nB phone\nc\n", "a\nB pc\nc\n")
        assertTrue(result.conflicted)
        assertEquals("a\n<<<<<<< phone\nB phone\n=======\nB pc\n>>>>>>> PC\nc\n", result.text)
        assertTrue(TextMerge.hasConflict(result.text))
    }

    @Test
    fun aClashCanBeSettledForEitherSideOrBoth() {
        val text = TextMerge.merge("a\nb\nc\n", "a\nB phone\nc\n", "a\nB pc\nc\n").text
        assertEquals("a\nB phone\nc\n", TextMerge.resolve(text, TextMerge.Keep.PHONE))
        assertEquals("a\nB pc\nc\n", TextMerge.resolve(text, TextMerge.Keep.PC))
        assertEquals("a\nB phone\nB pc\nc\n", TextMerge.resolve(text, TextMerge.Keep.BOTH))
        assertEquals(null, TextMerge.resolve("<<<<<<< phone\n<<<<<<< phone\n", TextMerge.Keep.PC))
    }

    @Test
    fun bothCreatingTheSameFileKeepsBoth() {
        val result = TextMerge.merge(null, "phone's\n", "pc's\n")
        assertTrue(result.conflicted)
        assertTrue(result.text.contains("phone's") && result.text.contains("pc's"))
    }

    @Test
    fun identicalEditsAreNotAConflict() {
        val result = TextMerge.merge("a\n", "a\nb\n", "a\nb\n")
        assertFalse(result.conflicted)
        assertEquals("a\nb\n", result.text)
    }

    @Test
    fun aMissingLastNewlineSurvives() {
        val result = TextMerge.merge("a\nx\nb", "a phone\nx\nb", "a\nx\nb pc")
        assertFalse(result.conflicted)
        assertEquals("a phone\nx\nb pc", result.text)
    }

    @Test
    fun editsToNeighbouringLinesClashAsInGit() {
        // git, and so the PC, treats changes with no unchanged line between them as one clash.
        assertTrue(TextMerge.merge("a\nb\n", "a phone\nb\n", "a\nb pc\n").conflicted)
    }
}
