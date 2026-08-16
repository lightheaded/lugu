package io.github.lightheaded.lugu.core.model

/**
 * How lugu writes down a time, a length and a speed.
 *
 * These were four functions in three feature modules, each written for the screen in front
 * of whoever wrote it, and they had already begun to disagree — a length was "1 h 20 min"
 * on the player and "1h 20m" in a list, and a speed was "2" on one chip and "2x" on
 * another. That kind of drift is invisible in review and obvious on a phone, because the
 * two readings sit a scroll apart.
 *
 * Collected here rather than deduplicated away: the difference between a *place* and a
 * *length* is real and worth keeping, and so is the difference between a roomy line and a
 * dense one. What was not worth keeping is each module deciding that on its own.
 */

/**
 * A place in the audio: "1:02:03", or "5:04" under an hour.
 *
 * Colons mean a timestamp. Everything that points at a position — the scrubber, a chapter
 * row, a bookmark — uses this, because two of them disagreeing by a rounding rule is the
 * sort of thing that gets reported as lost progress.
 */
fun formatClock(seconds: Double): String {
    val safe = seconds.coerceAtLeast(0.0).toLong()
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val secs = safe % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}

/**
 * A length rather than a place: "1 h 20 min".
 *
 * Units rather than colons, because a chapter list shows both and "0:12:04" in a length
 * column invites being read as a timestamp. Seconds appear only when there is nothing
 * larger to say, which is the case a chapter list actually hits.
 */
fun formatLength(seconds: Double): String {
    val safe = seconds.coerceAtLeast(0.0).toLong()
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "$hours h $minutes min"
        hours > 0 -> "$hours h"
        minutes > 0 -> "$minutes min"
        else -> "$safe s"
    }
}

/**
 * The same length with the spaces squeezed out: "1h 20m".
 *
 * For list sublines and badges, where a length shares a line with two or three other facts
 * and the separators are doing more work than the units. Deliberately a second function
 * rather than a `dense: Boolean` parameter on [formatLength], so a call site reads as a
 * choice about the space available rather than as a flag nobody can decode later.
 */
fun formatLengthCompact(seconds: Double): String {
    val safe = seconds.coerceAtLeast(0.0).toLong()
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${safe}s"
    }
}

/** Short form for a settings figure, where "300s" would be read as a mistake. */
fun formatShortSeconds(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return if (safe >= 60 && safe % 60 == 0) "${safe / 60} min" else "${safe}s"
}

/**
 * A speed without its trailing ".0": "2", "1.25".
 *
 * The zero matters because a chip is narrow — "2.0x" wraps onto two lines where "2x" does
 * not — and because a book played at exactly double speed is not more precisely described
 * by saying so to one decimal place.
 *
 * Rounds to hundredths rather than truncating, which is a correction rather than a
 * preference. Speed is a `Float` stepped by 0.05, and 0.05 is not representable in binary:
 * sixteen presses of the faster button from 1.0 lands on 1.7999992, and twenty lands on
 * 1.9999990. Truncating those printed "1.79x" and "1.99x" — a chip that disagrees with
 * the button the listener just pressed, and the sort of thing that reads as the setting
 * not having taken. Rounding first also makes the whole-number test exact, so 1.9999990
 * is "2x" rather than nearly it.
 */
fun formatSpeedNumber(speed: Float): String {
    val hundredths = kotlin.math.round(speed * 100.0).toLong()
    return if (hundredths % 100L == 0L) {
        (hundredths / 100L).toString()
    } else {
        (hundredths / 100.0).toString()
    }
}

/** The same number wearing its unit: "2x". */
fun formatSpeed(speed: Float): String = "${formatSpeedNumber(speed)}x"

/**
 * How one part-heard thing names itself in a Continue row.
 *
 * A show with three episodes on the go is three rows, and three rows are only useful if
 * each says which episode it is — so an episode names itself and lets the show become the
 * subtitle. That is the reverse of a podcast's own screen, where the show is already
 * known. A book has no episode and keeps its own title with its author underneath.
 *
 * An episode the mirror has not seen yet falls back to the item's title rather than to a
 * blank: a row that reads imprecisely is better than one that cannot be read at all.
 *
 * Lives here because the car and the phone both draw this row, and for a while they each
 * had their own copy of the rule — the same decision in two places, free to drift apart
 * the moment either was touched.
 */
object ContinueLabel {

    fun title(itemTitle: String, episodeTitle: String?): String =
        episodeTitle?.takeIf { it.isNotBlank() } ?: itemTitle

    fun subtitle(itemTitle: String, author: String?, episodeTitle: String?): String? =
        if (episodeTitle.isNullOrBlank()) author else itemTitle
}
