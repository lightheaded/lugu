package io.github.lightheaded.lugu.core.model

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * How far to jump back when playback resumes after a pause.
 *
 * Two rules, both aimed at the official app's headset complaints (app #1147, #1048,
 * #622, #230, #578):
 *
 *  1. The rewind is computed **once, at resume**, from how long the pause actually
 *     lasted — not applied at pause time. Rewinding on pause is what makes a position
 *     drift backwards a little on every pause until a book is minutes out of place.
 *  2. A short pause rewinds nothing. Headsets emit spurious pause/play pairs, and a
 *     pause the listener never noticed must not move their position.
 *
 * Between the two thresholds the amount grows with the logarithm of the pause, so a
 * two-minute interruption costs a few seconds and an overnight one costs the full
 * amount, without a cliff edge anywhere.
 */
object SmartRewind {

    data class Settings(
        /** Pauses shorter than this rewind nothing at all. */
        val minPauseSec: Long = 30,
        /** Pause length at which the full [maxRewindSec] applies. */
        val fullRewindAfterSec: Long = 3_600,
        val maxRewindSec: Double = 30.0,
        val enabled: Boolean = true,
    ) {
        init {
            require(minPauseSec > 0) { "minPauseSec must be positive" }
            require(fullRewindAfterSec > minPauseSec) { "fullRewindAfterSec must exceed minPauseSec" }
        }
    }

    /**
     * Seconds to rewind after a pause of [pausedForMs]. Always in [0, maxRewindSec],
     * and always zero for a pause shorter than the floor.
     */
    fun rewindSeconds(pausedForMs: Long, settings: Settings = Settings()): Double {
        if (!settings.enabled || pausedForMs <= 0) return 0.0

        val pausedSec = pausedForMs / 1000.0
        if (pausedSec < settings.minPauseSec) return 0.0
        if (pausedSec >= settings.fullRewindAfterSec) return settings.maxRewindSec

        // Logarithmic between the thresholds: most of the useful resolution is in the
        // first few minutes, which is where real interruptions land.
        val fraction = ln(pausedSec / settings.minPauseSec) /
            ln(settings.fullRewindAfterSec.toDouble() / settings.minPauseSec)
        return (settings.maxRewindSec * fraction).coerceIn(0.0, settings.maxRewindSec)
    }

    /**
     * The new position after resuming, clamped so a rewind can never run past the
     * start of the book.
     */
    fun resumePosition(
        currentSec: Double,
        pausedForMs: Long,
        settings: Settings = Settings(),
    ): Double = (currentSec - rewindSeconds(pausedForMs, settings)).coerceAtLeast(0.0)

    /** Wording for the "rewound 12s" hint. Null when nothing moved, so the UI stays quiet. */
    fun describe(pausedForMs: Long, settings: Settings = Settings()): String? {
        val seconds = rewindSeconds(pausedForMs, settings).roundToInt()
        return if (seconds <= 0) null else "Rewound ${seconds}s"
    }
}
