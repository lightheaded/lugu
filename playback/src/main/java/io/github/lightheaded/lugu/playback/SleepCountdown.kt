package io.github.lightheaded.lugu.playback

import io.github.lightheaded.lugu.core.model.Chapter
import io.github.lightheaded.lugu.core.model.Chapters
import io.github.lightheaded.lugu.core.model.SleepMode
import io.github.lightheaded.lugu.core.model.SleepTimer

/**
 * The sleep timer as the service ticks it, rather than as the arithmetic states it.
 *
 * `SleepTimer` answers the question "where should this timer stop the book, given where the
 * book is now". That is the right question for a listener who has just skipped, and the
 * wrong one for a book that is simply playing, and the difference is what this exists for.
 *
 * ### Why a chapter count needs its own answer
 *
 * `SleepTimer.stopPositionSec` resolves `Chapters(n)` from the position it is handed: the
 * end of the (n-1)th chapter *after the one the book is in*. Ordinary playback moves that
 * position, so crossing into the next chapter moves the target on by a whole chapter as
 * well, and the timer recedes at exactly the speed it is approached. `Chapters(2)` would
 * never come due until the book ran out of chapters to recede into — the timer would appear
 * to do nothing at all, which is the one failure a sleep timer must not have.
 *
 * So the count is resolved once, from the position the timer was armed at. A listener who
 * skips *past* that target has already had the chapters they asked for, and is given the
 * end of the chapter they landed in rather than an immediate stop, which would be a strange
 * reward for pressing a button.
 *
 * ### Why a boundary needs a tolerance
 *
 * The other two modes reach their target by the position walking up to it, and the position
 * only moves in tick-sized steps. A chapter that ends at 600 s is seen at 599.7 and then at
 * 600.2, by which time end-of-chapter has resolved to the *next* chapter's end and the
 * boundary has been stepped clean over. A timer that fires within a tick of its target is
 * doing what it was set to do; one that silently rolls into the next chapter is the bug
 * upstream reports as chapters being ignored.
 */
object SleepCountdown {

    /** Playback seconds left before the timer stops the book. Null when it cannot come due. */
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

    /** Where the timer stops the book, in whole-book seconds. */
    fun stopPositionSec(
        mode: SleepMode?,
        chapters: List<Chapter>,
        positionSec: Double,
        armedAtPositionSec: Double,
        speed: Float = 1.0f,
    ): Double? {
        if (mode !is SleepMode.Chapters) {
            return SleepTimer.stopPositionSec(mode, chapters, positionSec, armedAtPositionSec, speed)
        }
        // Resolved from where it was armed, which is the whole point — see above.
        val target = SleepTimer.stopPositionSec(mode, chapters, armedAtPositionSec, armedAtPositionSec, speed)
            ?: return null
        if (positionSec < target) return target
        return Chapters.at(chapters, positionSec)?.endSec ?: target
    }

    /**
     * Whether the timer has come due at [remainingSec], for a loop that ticks every
     * [tickMs] at [speed].
     *
     * The tolerance is exactly one tick of content, so the timer can be at most one tick
     * early and can never be a whole chapter late.
     */
    fun isDue(remainingSec: Double?, tickMs: Long, speed: Float): Boolean {
        if (remainingSec == null) return false
        val tickSeconds = tickMs / 1000.0 * speed.coerceAtLeast(0.01f)
        return remainingSec <= tickSeconds
    }
}
