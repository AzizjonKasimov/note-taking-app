package com.azizjon.notes.voice

/** Longest piece of a recording the model hears at once; its package says to split long audio at pauses into 15-20 s. */
internal const val MAX_PIECE_SAMPLES = 20 * SAMPLE_RATE

/**
 * The pieces a recording is cut into for the model: cut in the middle of pauses, so no piece runs
 * past [maxSamples], and pieces with no speech in them are left out. Parakeet loses words on long
 * clips, and cutting inside a pause, rather than at the voice detector's edges, keeps every word
 * whole. [speech] is the detected speech, in order; a stretch with no pause at all is cut hard.
 */
internal fun pieces(speech: List<IntRange>, total: Int, maxSamples: Int = MAX_PIECE_SAMPLES): List<IntRange> {
    if (speech.isEmpty() || total <= 0) return emptyList()
    val pauses = speech.zipWithNext { a, b -> (a.last + 1 + b.first) / 2 }
    val out = mutableListOf<IntRange>()
    var start = 0
    var next = 0
    while (total - start > maxSamples) {
        var cut = -1
        while (next < pauses.size && pauses[next] - start <= maxSamples) {
            if (pauses[next] > start) cut = pauses[next]
            next++
        }
        if (cut < 0) cut = start + maxSamples
        out += start until cut
        start = cut
    }
    out += start until total
    return out.filter { piece -> speech.any { it.first <= piece.last && it.last >= piece.first } }
}

/** While recording, a piece is heard once it holds this much: long enough for context, short enough to keep up. */
internal const val LIVE_PIECE_SAMPLES = 8 * SAMPLE_RATE

/** Silence kept after a stretch of speech when a piece is cut there (the detector waits 0.3 s of silence). */
internal const val CUT_PAD_SAMPLES = SAMPLE_RATE * 15 / 100

/**
 * Where to cut a recording that is still going on, so its pieces can be heard while the user talks:
 * just after a finished stretch of speech, once the open piece (from [lastCut] to [total]) holds at
 * least [target] samples; null to wait for more. A piece about to run past [max] is cut at the last
 * pause in it even if short, and with no pause at all it is cut hard.
 */
internal fun liveCut(
    speech: List<IntRange>,
    lastCut: Int,
    total: Int,
    target: Int = LIVE_PIECE_SAMPLES,
    max: Int = MAX_PIECE_SAMPLES,
    pad: Int = CUT_PAD_SAMPLES,
): Int? {
    val fit = speech.asSequence()
        .map { minOf(it.last + 1 + pad, total) }
        .filter { it > lastCut && it - lastCut <= max }
        .lastOrNull()
    return when {
        fit != null && fit - lastCut >= target -> fit
        total - lastCut > max -> fit ?: (lastCut + max)
        else -> null
    }
}

/**
 * The pieces' texts as one passage. The model starts every piece with a capital, so a piece that
 * continues a sentence (the one before ends without . ! or ?) gets its first word back in lower
 * case, when that word is a common one: names keep their capitals.
 */
internal fun joinPieces(texts: List<String>): String {
    val parts = texts.map { it.trim() }.filter { it.isNotEmpty() }
    return buildString {
        parts.forEachIndexed { i, part ->
            if (i == 0) {
                append(part)
                return@forEachIndexed
            }
            val continues = last().let { it != '.' && it != '!' && it != '?' && it != '…' }
            append(' ')
            val first = part.substringBefore(' ').trimEnd(',', ';', ':')
            if (continues && first.lowercase() in COMMON_WORDS && first != "I" && first[0].isUpperCase()) {
                append(part[0].lowercaseChar()).append(part, 1, part.length)
            } else {
                append(part)
            }
        }
    }
}

private val COMMON_WORDS = (
    "a about after also an and are as at be because been before but by can could did do does for from " +
        "had has have he her here his how if in into is it its just like may maybe me might more my no not " +
        "now of on one or our out she should so some such than that the their them then there these they " +
        "this those to too up us very was we were what when where which while who why will with would yes " +
        "yet you your"
    ).split(' ').toSet()

/** Where dictated text goes in a note's text, and with which spaces around it. */
internal data class Placement(val index: Int, val text: String)

/**
 * At the cursor when the writer placed one, with a space on each side where the neighbouring
 * characters need it; otherwise as a new paragraph at the end, after [paragraphBreak] (a blank
 * line in Markdown text, where a single line break would join the last paragraph or list item).
 * An empty note takes the text as is.
 */
internal fun placeDictation(
    noteText: String,
    cursor: Int?,
    dictated: String,
    paragraphBreak: String = "\n",
): Placement {
    val words = dictated.trim()
    if (noteText.isBlank()) return Placement(0, words)
    if (cursor == null) {
        val end = noteText.trimEnd().length
        return Placement(end, paragraphBreak + words)
    }
    val at = cursor.coerceIn(0, noteText.length)
    val before = noteText.getOrNull(at - 1)
    val after = noteText.getOrNull(at)
    val lead = if (before == null || before.isWhitespace()) "" else " "
    val trail = if (after == null || after.isWhitespace() || after in ".,;:!?)") "" else " "
    return Placement(at, lead + words + trail)
}

/** Names to boost, one per line; blank lines and lines starting with # are skipped. */
internal fun parseNames(text: String): List<String> =
    text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.distinct().toList()
