package io.github.lightheaded.lugu.core.model

/**
 * One series a book belongs to, as the server actually models it.
 *
 * A book belongs to *any number* of series — the server keeps the membership in a join
 * table with the sequence on the join row — so this is the shape everything downstream
 * should think in, and the flat `seriesName` string is only a rendering of a list of
 * these. [id] is the server's own identifier where it was handed one, and null where the
 * membership had to be recovered from that rendering instead.
 *
 * [sequence] is null both when the server has no sequence for this book and when the one
 * it has is not a number ("Book Two", "IV", "2a" are all things a tagger can type into
 * that field). Both cases mean the same thing to anything that orders a series: no
 * position is known, so none is claimed.
 */
data class SeriesRef(
    val id: String?,
    val name: String,
    val sequence: Double?,
)

/**
 * Series names, and the number hiding inside them.
 *
 * The list endpoints hand clients a single string — "The Breakwater #2" — rather than a
 * name and a sequence. Any feature that orders a series therefore has to get the number
 * out first, because sorting the string puts "#10" before "#2" and would recommend book
 * ten to someone who has just finished book one.
 *
 * Anything that does not parse cleanly returns null rather than a guess. A shelf that
 * silently omits an oddly-named entry is a small disappointment; one that confidently
 * suggests the wrong volume of a series is a spoiler.
 *
 * The rendering is *lossy*, which is why [sequenceOf] and [titleOf] are the last resort
 * rather than the first. A book in two series renders as one string with both in it —
 * "The Breakwater #1, The Tidelands #3" — and [sequenceOf] reads the trailing number of
 * that, which belongs to the wrong series. Where the series is already known, take the
 * name-anchored [sequenceWithin] instead; where the server sent a structured membership,
 * take [parseSequence] on its own sequence field and do not come here at all.
 */
object Series {
    /** "Name #2", "Name #2.5", "Name # 3" — the shapes the server actually emits. */
    private val WITH_SEQUENCE = Regex("""^(.*?)\s*#\s*(\d+(?:\.\d+)?)\s*$""")

    /**
     * How the server joins several series into one string, in `Book.seriesName`.
     *
     * A series name may itself contain a comma, so this is never used to *split* the
     * string — only to check what sits either side of a name already known to be in it.
     */
    private const val SEPARATOR = ", "

    /** The only sequence shape read as a position. Everything else is somebody's prose. */
    private val PLAIN_NUMBER = Regex("""^\d+(?:\.\d+)?$""")

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

    /**
     * The server's own sequence field, read as a position — or null.
     *
     * That field is free text on the server: it is a string column with no validation, so
     * "2", "2.5", "Book Two", "IV" and "2a" are all things it can hold. Only a plain
     * number is accepted. The server itself is looser — it orders series with
     * `CAST(sequence AS FLOAT)`, which quietly reads "2a" as 2 and "Book Two" as 0, and
     * so puts everything unnumbered at the front of a series in the order the scanner
     * happened to find it. Reading zero as a position is exactly the confident wrong
     * answer this whole file exists to avoid.
     */
    fun parseSequence(rawSequence: String?): Double? {
        val text = rawSequence?.trim() ?: return null
        if (!PLAIN_NUMBER.matches(text)) return null
        return text.toDoubleOrNull()
    }

    /**
     * The sequence a *named* series carries inside the joined string, or null.
     *
     * The lossiness of the rendering is almost entirely an ambiguity about where one
     * series ends and the next begins, and knowing which series is being asked about
     * removes it: "The Breakwater #1, The Tidelands #3" answers 1.0 for The Breakwater
     * and 3.0 for The Tidelands, where [sequenceOf] answers 3.0 for the whole string and
     * would file the book under a series called "The Breakwater #1, The Tidelands".
     *
     * Anchoring on the name also rescues the single-series case the plain parse gets
     * wrong in the other direction: a series called "Riverton, The" keeps its comma,
     * because the name is matched whole rather than the string being split on commas.
     *
     * Null whenever the answer is not unambiguous — the name is not in the string, or is
     * in it twice, or is there without a number.
     */
    fun sequenceWithin(joinedName: String?, seriesName: String): Double? {
        val haystack = joinedName?.trim().orEmpty()
        val needle = seriesName.trim()
        if (haystack.isEmpty() || needle.isEmpty()) return null

        var answer: String? = null
        var searchFrom = 0
        while (searchFrom <= haystack.length - needle.length) {
            val at = haystack.indexOf(needle, searchFrom, ignoreCase = true)
            if (at < 0) break
            searchFrom = at + 1

            // Only a whole entry counts. Without this, "The Breakwater" would match
            // inside "The Breakwater Companion" and claim that book's number.
            if (at != 0 && !haystack.startsWith(SEPARATOR, at - SEPARATOR.length)) continue
            val tail = haystack.substring(at + needle.length)
            val found = when {
                tail.isEmpty() || tail.startsWith(SEPARATOR) -> ""
                tail.startsWith(" #") -> tail.removePrefix(" #").substringBefore(SEPARATOR)
                else -> continue
            }

            // The same name twice is a string nobody can read reliably, so nobody should.
            if (answer != null) return null
            answer = found
        }
        return parseSequence(answer)
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
