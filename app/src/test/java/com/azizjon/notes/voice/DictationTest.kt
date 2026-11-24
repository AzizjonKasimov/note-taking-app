package com.azizjon.notes.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationTest {
    private val second = SAMPLE_RATE

    @Test
    fun shortRecordingIsOnePiece() {
        assertEquals(listOf(0 until 5 * second), pieces(listOf(second until 4 * second), 5 * second))
    }

    @Test
    fun silenceOnlyGivesNoPieces() {
        assertEquals(emptyList<IntRange>(), pieces(emptyList(), 30 * second))
    }

    @Test
    fun longRecordingIsCutInTheMiddleOfPauses() {
        // Speech 0-12 s, 13-25 s, 26-38 s: pauses centred at 12.5 s and 25.5 s.
        val speech = listOf(0 until 12 * second, 13 * second until 25 * second, 26 * second until 38 * second)
        val got = pieces(speech, 38 * second)
        assertEquals(
            listOf(0 until (12.5 * second).toInt(), (12.5 * second).toInt() until (25.5 * second).toInt(),
                (25.5 * second).toInt() until 38 * second),
            got,
        )
        assertTrue(got.all { it.last - it.first + 1 <= MAX_PIECE_SAMPLES })
    }

    @Test
    fun piecesTakeAsManyPausesAsFit() {
        // Short phrases every 5 s: each piece should run up to the last pause within 20 s.
        val speech = (0 until 12).map { k -> (k * 5 * second) until (k * 5 * second + 4 * second) }
        val got = pieces(speech, 60 * second)
        assertTrue(got.all { it.last - it.first + 1 <= MAX_PIECE_SAMPLES })
        assertEquals(0, got.first().first)
        assertEquals(60 * second - 1, got.last().last)
        assertEquals(4, got.size)
    }

    @Test
    fun speechWithNoPauseIsCutHard() {
        val got = pieces(listOf(0 until 45 * second), 45 * second)
        assertEquals(listOf(0 until 20 * second, 20 * second until 40 * second, 40 * second until 45 * second), got)
    }

    @Test
    fun aLongSilentStretchIsLeftOut() {
        val speech = listOf(0 until 5 * second, 50 * second until 55 * second)
        val got = pieces(speech, 60 * second)
        assertTrue(got.none { piece -> piece.first > 6 * second && piece.last < 49 * second })
        assertTrue(got.any { 0 in it } && got.any { 52 * second in it })
    }

    @Test
    fun liveWaitsUntilThePieceIsLongEnough() {
        // 5 s of speech, then silence: too short to hear yet.
        assertEquals(null, liveCut(listOf(0 until 5 * second), lastCut = 0, total = 6 * second))
    }

    @Test
    fun liveCutsJustAfterTheLastFinishedSpeech() {
        val speech = listOf(0 until 5 * second, 6 * second until 10 * second)
        assertEquals(10 * second + CUT_PAD_SAMPLES, liveCut(speech, lastCut = 0, total = 11 * second))
        // The next piece starts there: the old speech no longer counts.
        assertEquals(null, liveCut(speech, lastCut = 10 * second + CUT_PAD_SAMPLES, total = 12 * second))
    }

    @Test
    fun liveCutsAShortPieceRatherThanRunPastTheLimit() {
        // A 1 s phrase, then speech that has not paused for 20 s: cut after the phrase.
        val speech = listOf(0 until second)
        assertEquals(second + CUT_PAD_SAMPLES, liveCut(speech, lastCut = 0, total = 21 * second))
    }

    @Test
    fun liveCutsHardWhenSpeechNeverPauses() {
        assertEquals(MAX_PIECE_SAMPLES, liveCut(emptyList(), lastCut = 0, total = 21 * second))
    }

    @Test
    fun piecesAreJoinedWithSingleSpaces() {
        assertEquals("One. Two three.", joinPieces(listOf(" One. ", "", "Two three.")))
    }

    @Test
    fun aPieceThatContinuesASentenceLosesItsCapital() {
        assertEquals(
            "I sort my bank statement, but I don't let it touch the numbers.",
            joinPieces(listOf("I sort my bank statement,", "But I don't let it touch the numbers.")),
        )
        assertEquals("Then the AI writes. And done.", joinPieces(listOf("Then the AI writes.", "And done.")))
    }

    @Test
    fun namesAndIKeepTheirCapitals() {
        assertEquals("I met Balim and Coupang called", joinPieces(listOf("I met", "Balim and", "Coupang called")))
        assertEquals("so I think I can", joinPieces(listOf("so", "I think I can")))
    }

    @Test
    fun dictationFillsAnEmptyNote() {
        assertEquals(Placement(0, "Hello there."), placeDictation("", null, " Hello there. "))
    }

    @Test
    fun dictationWithoutACursorIsANewParagraphAtTheEnd() {
        assertEquals(Placement(9, "\nSecond."), placeDictation("First one\n\n", null, "Second."))
        // In Markdown text a paragraph needs a blank line, or it joins the list item above.
        assertEquals(Placement(6, "\n\nSecond."), placeDictation("- milk\n", null, "Second.", paragraphBreak = "\n\n"))
    }

    @Test
    fun dictationAtTheCursorGetsSpaces() {
        assertEquals(Placement(4, " big"), placeDictation("Some text.", 4, "big"))
        assertEquals(Placement(4, " big "), placeDictation("Somewords", 4, "big"))
        assertEquals(Placement(5, "big "), placeDictation("Some text", 5, "big"))
        assertEquals(Placement(4, " big"), placeDictation("Some.", 4, "big"))
    }

    @Test
    fun namesSkipBlanksCommentsAndRepeats() {
        assertEquals(listOf("Balim", "Olive Young"), parseNames("Balim\n\n# a comment\n  Olive Young \nBalim\n"))
    }

    @Test
    fun vocabularyRanksPiecesByTheirIds() {
        assertEquals("<unk>\t0\n▁t\t-1\nth\t-2\n", bpeVocab("<unk> 0\n▁t 1\nth 2\n<blk> 3\n"))
    }
}
