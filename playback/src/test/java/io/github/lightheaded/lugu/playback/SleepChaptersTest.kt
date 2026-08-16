package io.github.lightheaded.lugu.playback

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.Chapter
import io.github.lightheaded.lugu.core.model.SleepMode
import org.junit.Test

/**
 * "Two more chapters", from arming through the fade to the stop.
 *
 * The loop being tested is the one the playback service runs: remaining time, then the
 * fade, then the decision to stop. A mode can be perfectly right in the arithmetic and
 * still never come due in that loop — which is what a listener experiences as the timer
 * being broken, and is exactly what [SleepCountdown] exists to prevent.
 */
class SleepChaptersTest {

    private val chapters = listOf(
        Chapter(0, 0.0, 600.0, "Chapter 1"),
        Chapter(1, 600.0, 1_200.0, "Chapter 2"),
        Chapter(2, 1_200.0, 1_800.0, "Chapter 3"),
    )

    private val tickMs = 500L

    /** One tick of the service loop: the volume to set, and whether to stop. */
    private fun tick(
        mode: SleepMode,
        positionSec: Double,
        armedAtSec: Double,
        speed: Float = 1.0f,
        fadeSeconds: Int = 20,
    ): Pair<Float, Boolean> {
        val remaining = SleepCountdown.remainingSec(mode, chapters, positionSec, armedAtSec, speed)
        return SleepFade.volumeFor(remaining, fadeSeconds) to SleepCountdown.isDue(remaining, tickMs, speed)
    }

    /**
     * The failure this whole class is about: resolved from the current position, the target
     * moves on by a chapter every time the book crosses into one, so the timer is never
     * reached. Resolved from the armed position, two chapters means two chapters.
     */
    @Test
    fun `a chapter count does not recede as the book plays into it`() {
        val armedAt = 100.0

        assertThat(tick(SleepMode.Chapters(2), positionSec = 100.0, armedAtSec = armedAt)).isEqualTo(1.0f to false)
        assertThat(tick(SleepMode.Chapters(2), positionSec = 700.0, armedAtSec = armedAt)).isEqualTo(1.0f to false)
        assertThat(tick(SleepMode.Chapters(2), positionSec = 1_199.8, armedAtSec = armedAt).second).isTrue()
    }

    @Test
    fun `one chapter is the end of the chapter it was armed in`() {
        assertThat(SleepCountdown.stopPositionSec(SleepMode.Chapters(1), chapters, 100.0, 100.0)).isEqualTo(600.0)
    }

    @Test
    fun `the fade runs down through the closing seconds`() {
        val early = tick(SleepMode.Chapters(1), positionSec = 400.0, armedAtSec = 0.0).first
        val late = tick(SleepMode.Chapters(1), positionSec = 590.0, armedAtSec = 0.0).first

        assertThat(early).isEqualTo(1.0f)
        assertThat(late).isLessThan(1.0f)
        assertThat(late).isGreaterThan(0.0f)
    }

    /**
     * Skipping past the chapters that were asked for does not stop the book on the spot.
     * The listener has had them; the honest place to stop is the end of the one they are
     * now in.
     */
    @Test
    fun `skipping past the count stops at the end of the chapter landed in`() {
        val stopAt = SleepCountdown.stopPositionSec(
            SleepMode.Chapters(2),
            chapters,
            positionSec = 1_300.0,
            armedAtPositionSec = 100.0,
        )

        assertThat(stopAt).isEqualTo(1_800.0)
    }

    /** Asking for more chapters than the book has left means "to the end", not "never". */
    @Test
    fun `asking for more chapters than remain stops at the end`() {
        assertThat(tick(SleepMode.Chapters(9), positionSec = 1_799.9, armedAtSec = 1_300.0).second).isTrue()
    }

    /**
     * A boundary the loop would otherwise step over. At 1.5x the position moves three
     * quarters of a second per tick, so the end of a chapter is seen from just before it
     * and then from just after, by which time end-of-chapter has resolved to the next one.
     */
    @Test
    fun `an end of chapter timer is not stepped over between ticks`() {
        val justBefore = tick(SleepMode.EndOfChapter, positionSec = 599.6, armedAtSec = 0.0, speed = 1.5f)

        assertThat(justBefore.second).isTrue()
    }

    @Test
    fun `an item with no chapters never comes due`() {
        val remaining = SleepCountdown.remainingSec(SleepMode.Chapters(2), emptyList(), 100.0, 100.0)

        assertThat(remaining).isNull()
        assertThat(SleepCountdown.isDue(remaining, tickMs, speed = 1.0f)).isFalse()
        assertThat(SleepFade.volumeFor(remaining, fadeSeconds = 20)).isEqualTo(1.0f)
    }

    /** A duration is unaffected by any of this and must stay unaffected. */
    @Test
    fun `a duration still counts in playback seconds`() {
        val remaining = SleepCountdown.remainingSec(SleepMode.Duration(10), chapters, 300.0, 300.0, speed = 1.0f)

        assertThat(remaining).isEqualTo(600.0)
    }
}
