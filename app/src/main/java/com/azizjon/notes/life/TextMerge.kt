package com.azizjon.notes.life

import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.merge.MergeAlgorithm
import org.eclipse.jgit.merge.MergeFormatter
import java.io.ByteArrayOutputStream

/** One file after a three-way merge: its bytes, and whether both sides changed the same lines. */
class TextMergeResult(val bytes: ByteArray, val conflicted: Boolean) {
    val text: String get() = bytes.toString(Charsets.UTF_8)
}

/**
 * Three-way merge of one text file, so a note changed on the phone and on the PC keeps both.
 *
 * Changes to different lines are combined. Where both sides changed the same lines, both versions
 * stay in the file between the standard conflict markers (`<<<<<<< phone`, `=======`,
 * `>>>>>>> PC`), which the app, git, and agents on the PC all recognize. Nothing is dropped.
 */
object TextMerge {
    const val PHONE = "phone"
    const val OTHER = "PC"
    const val START_MARKER = "<<<<<<< $PHONE"
    const val END_MARKER = ">>>>>>> $OTHER"

    private val algorithm =
        MergeAlgorithm(DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM))

    /** [base] is null when neither side had the file before (both created it). */
    fun merge(base: ByteArray?, phone: ByteArray, other: ByteArray): TextMergeResult {
        if (phone.contentEquals(other)) return TextMergeResult(phone, conflicted = false)
        if (base != null && base.contentEquals(phone)) return TextMergeResult(other, conflicted = false)
        if (base != null && base.contentEquals(other)) return TextMergeResult(phone, conflicted = false)
        val result = algorithm.merge(
            RawTextComparator.DEFAULT,
            RawText(base ?: ByteArray(0)),
            RawText(phone),
            RawText(other),
        )
        val out = ByteArrayOutputStream()
        MergeFormatter().formatMerge(out, result, listOf("base", PHONE, OTHER), Charsets.UTF_8)
        return TextMergeResult(out.toByteArray(), result.containsConflicts())
    }

    fun merge(base: String?, phone: String, other: String): TextMergeResult =
        merge(base?.toByteArray(Charsets.UTF_8), phone.toByteArray(Charsets.UTF_8), other.toByteArray(Charsets.UTF_8))

    /** True for files JGit would not merge line by line (NUL bytes near the start). */
    fun isBinary(bytes: ByteArray): Boolean = RawText.isBinary(bytes)

    /** True when [text] still holds a block of both versions from a clash. */
    fun hasConflict(text: String): Boolean =
        text.contains(START_MARKER) && text.contains(END_MARKER)

    enum class Keep { PHONE, PC, BOTH }

    /**
     * Replaces every block of both versions with the side [keep] names (both: the phone's lines,
     * then the PC's). Null when the markers are not in order, such as one block inside another;
     * the note is then left for the user to edit by hand.
     */
    fun resolve(text: String, keep: Keep): String? {
        val out = ArrayList<String>()
        var phone: MutableList<String>? = null
        var other: MutableList<String>? = null
        for (line in text.split("\n")) {
            when {
                line == START_MARKER && phone != null -> return null
                line == START_MARKER -> phone = ArrayList()
                line == MIDDLE_MARKER && phone != null && other == null -> other = ArrayList()
                line == END_MARKER && other != null -> {
                    if (keep != Keep.PC) out += phone!!
                    if (keep != Keep.PHONE) out += other
                    phone = null
                    other = null
                }
                other != null -> other += line
                phone != null -> phone += line
                else -> out += line
            }
        }
        return if (phone == null) out.joinToString("\n") else null
    }

    private const val MIDDLE_MARKER = "======="
}
