package io.github.lightheaded.lugu.core.model

/** What the sleep timer is counting down to. */
sealed interface SleepMode {
    /** A fixed wall-clock duration. */
    data class Duration(val minutes: Int) : SleepMode

    /**
     * Stop when the current chapter ends. Re-evaluated as playback moves, so skipping
     * a chapter re-arms against the new one instead of firing at the old boundary —
     * the official app's #780/#2835 class of bug.
     */
    data object EndOfChapter : SleepMode
}

/** The timer as the UI and the playback service both see it. */
data class SleepTimerState(
    val mode: SleepMode? = null,
    /** Seconds of playback left before the fade begins. Null when the timer is off. */
    val remainingSec: Double? = null,
    val isFading: Boolean = false,
) {
    val isArmed: Boolean get() = mode != null
}

/**
 * Sleep timer arithmetic, kept pure so it can be tested without waiting in real time.
 *
 * Two properties matter and neither is obvious:
 *
 *  - Remaining time is measured in **playback** seconds, not wall-clock, so a timer
 *    behaves the same at 1× and 2× and pausing does not burn it down.
 *  - End-of-chapter is recomputed from the current position on every tick rather than
 *    resolved once when armed. Skip a chapter and the timer follows you; resolving it
 *    up front is what makes sleep timers fire at the wrong moment.
 */
object SleepTimer {

    /** Where the timer should stop playback, in whole-book seconds. Null if not armed. */
    fun stopPositionSec(
        mode: SleepMode?,
        chapters: List<Chapter>,
        positionSec: Double,
        armedAtPositionSec: Double,
        speed: Float = 1.0f,
    ): Double? = when (mode) {
        null -> null

        is SleepMode.Duration -> {
            // A duration in minutes is minutes of listening, so at 2x it covers twice
            // as much of the book. Measuring in content seconds keeps that true.
            armedAtPositionSec + mode.minutes * 60.0 * speed.coerceAtLeast(0.01f)
        }

        SleepMode.EndOfChapter ->
            Chapters.at(chapters, positionSec)?.endSec ?: Chapters.nextChapterStart(chapters, positionSec)
    }

    /** Playback seconds remaining, floored at zero. Null when the timer is off. */
    fun remainingSec(
        mode: SleepMode?,
        chapters: List<Chapter>,
        positionSec: Double,
        armedAtPositionSec: Double,
        speed: Float = 1.0f,
    ): Double? {
        val stopAt = stopPositionSec(mode, chapters, positionSec, armedAtPositionSec, speed) ?: return null
        return (stopAt - positionSec).coerceAtLeast(0.0)
    }

    fun hasExpired(
        mode: SleepMode?,
        chapters: List<Chapter>,
        positionSec: Double,
        armedAtPositionSec: Double,
        speed: Float = 1.0f,
    ): Boolean {
        val remaining = remainingSec(mode, chapters, positionSec, armedAtPositionSec, speed) ?: return false
        return remaining <= 0.0
    }

    /**
     * Volume multiplier during the closing seconds, so playback fades rather than
     * stopping mid-word.
     */
    fun fadeVolume(remainingSec: Double?, fadeOverSec: Double = DEFAULT_FADE_SEC): Float {
        if (remainingSec == null) return 1.0f
        if (remainingSec >= fadeOverSec) return 1.0f
        return (remainingSec / fadeOverSec).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * Extending re-arms from the current position, which is what "shake to add 5
     * minutes" has to mean once the timer has already run down.
     */
    fun extend(mode: SleepMode?, byMinutes: Int): SleepMode = when (mode) {
        is SleepMode.Duration -> SleepMode.Duration(mode.minutes + byMinutes)
        else -> SleepMode.Duration(byMinutes)
    }

    const val DEFAULT_FADE_SEC = 20.0

    /** Offered as one-tap options. */
    val PRESET_MINUTES = listOf(5, 10, 15, 30, 45, 60, 90)
}
