package io.github.lightheaded.lugu.feature.library

import io.github.lightheaded.lugu.core.model.formatLengthCompact

/**
 * What the primary play button says: "Play", "Play again", or "Resume · 8h 51m left".
 *
 * Finished comes first, because a finished book sits at its end and "Resume" there would
 * play the last second and stop. The time left is in the button so that the page needs no
 * second line to say how far in somebody is. Without a known duration it is plain "Resume",
 * because "0s left" reads as a book about to end.
 */
internal fun playLabel(isFinished: Boolean, positionSec: Double, durationSec: Double): String {
    if (isFinished) return "Play again"
    if (positionSec <= 0.0) return "Play"
    val leftSec = durationSec - positionSec
    if (durationSec <= 0.0 || leftSec <= 0.0) return "Resume"
    return "Resume · ${formatLengthCompact(leftSec)} left"
}
