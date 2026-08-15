package io.github.lightheaded.lugu.core.model

/**
 * Series names, and the number hiding inside them.
 *
 * Audiobookshelf hands clients a single string — "The Breakwater #2" — rather than a name
 * and a sequence. Any feature that orders a series therefore has to get the number out
 * first, because sorting the string puts "#10" before "#2" and would recommend book ten
 * to someone who has just finished book one.
 *
 * Anything that does not parse cleanly returns null rather than a guess. A shelf that
 * silently omits an oddly-named entry is a small disappointment; one that confidently
 * suggests the wrong volume of a series is a spoiler.
 */
object Series {
    /** "Name #2", "Name #2.5", "Name # 3" — the shapes the server actually emits. */
    private val WITH_SEQUENCE = Regex("""^(.*?)\s*#\s*(\d+(?:\.\d+)?)\s*$""")

    fun sequenceOf(seriesName: String?): Double? {
        val match = WITH_SEQUENCE.matchEntire(seriesName?.trim().orEmpty()) ?: return null
        return match.groupValues[2].toDoubleOrNull()
    }

    /** The series name without its sequence suffix, for display. */
    fun titleOf(seriesName: String?): String? {
        val trimmed = seriesName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val match = WITH_SEQUENCE.matchEntire(trimmed) ?: return trimmed
        return match.groupValues[1].trim().takeIf { it.isNotEmpty() } ?: trimmed
    }

    /** "Book 2 of The Breakwater", or just the name when there is no number. */
    fun describe(seriesName: String?): String? {
        val title = titleOf(seriesName) ?: return null
        val sequence = sequenceOf(seriesName) ?: return title
        return "Book ${formatSequence(sequence)} of $title"
    }

    private fun formatSequence(sequence: Double): String =
        if (sequence % 1.0 == 0.0) sequence.toInt().toString() else sequence.toString()
}
