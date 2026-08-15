package io.github.lightheaded.lugu.feature.player

/**
 * Time formatting for the player, kept in one place so a position reads the same wherever
 * it appears — the scrubber, a chapter row and a bookmark are all the same clock, and two
 * of them disagreeing by a rounding rule is the sort of thing that gets reported as lost
 * progress.
 */

internal fun formatTime(seconds: Double): String {
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
 * A length rather than a place.
 *
 * A chapter list shows both, and showing a length as "0:12:04" invites it to be read as a
 * timestamp, so lengths get units and places get colons.
 */
internal fun formatDurationLabel(seconds: Double): String {
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

/** Short form for a settings figure, where "300s" would be read as a mistake. */
internal fun formatShortSeconds(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return if (safe >= 60 && safe % 60 == 0) "${safe / 60} min" else "${safe}s"
}

/**
 * How long it actually takes to reach an audio position at the current speed.
 *
 * Audio seconds are the book's own clock and never move; wall-clock time depends on the
 * speed being used at the time, so this is an estimate for the speed set right now and is
 * only ever shown labelled with it.
 */
internal fun wallClockSecondsAt(audioSec: Double, speed: Float): Double =
    audioSec / speed.coerceAtLeast(0.01f)
