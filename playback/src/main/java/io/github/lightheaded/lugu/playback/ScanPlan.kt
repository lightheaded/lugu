package io.github.lightheaded.lugu.playback

/**
 * Hold a seek button to scan: the player jumps a step, plays a short slice, and jumps again,
 * so the listener hears where they are and lets go at the place they want.
 *
 * The steps grow while the button is held. A short hold moves through a few minutes with
 * every sentence or two audible. A long hold crosses a chapter in seconds. Each step starts
 * from the last target, not from where the audio got to, so a scan back moves back the
 * whole step and does not lose the slice it just played.
 *
 * Kept apart from the player so the pace can be argued about in a test.
 */
internal object ScanPlan {

    /** How long each slice plays before the next jump. Long enough to hear a few words. */
    const val SLICE_MS = 700L

    /**
     * How close to the end a forward scan stops. Past the service's finished tail (20 s), so
     * a scan never marks a book as finished or ends it into the next one.
     */
    const val END_MARGIN_SEC = 30.0

    /** The jump for a hold that has lasted [heldMs]. */
    fun stepSec(heldMs: Long): Double = when {
        heldMs < 2_000L -> 5.0
        heldMs < 5_000L -> 15.0
        heldMs < 10_000L -> 30.0
        else -> 60.0
    }

    /**
     * The next target from [fromSec], whole-item seconds, or null when the scan is at its
     * edge: the start going back, or [END_MARGIN_SEC] before the end going forward.
     */
    fun nextTarget(fromSec: Double, forward: Boolean, stepSec: Double, durationSec: Double): Double? {
        if (forward) {
            val limit = (durationSec - END_MARGIN_SEC).coerceAtLeast(0.0)
            if (fromSec >= limit) return null
            return minOf(fromSec + stepSec, limit)
        }
        if (fromSec <= 0.0) return null
        return maxOf(fromSec - stepSec, 0.0)
    }
}
