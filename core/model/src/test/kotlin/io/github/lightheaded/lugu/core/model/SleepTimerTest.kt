package io.github.lightheaded.lugu.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SleepTimerTest {

    private val chapters = listOf(
        Chapter(0, 0.0, 600.0, "One"),
        Chapter(1, 600.0, 1_200.0, "Two"),
        Chapter(2, 1_200.0, 1_800.0, "Three"),
    )

    @Test
    fun `a duration timer counts down from where it was armed`() {
        val remaining = SleepTimer.remainingSec(
            mode = SleepMode.Duration(15),
            chapters = chapters,
            positionSec = 300.0,
            armedAtPositionSec = 0.0,
        )
        assertThat(remaining).isEqualTo(600.0)
    }

    /**
     * Fifteen minutes of listening should stay fifteen minutes of listening at 2x,
     * which means covering twice as much of the book.
     */
    @Test
    fun `a duration timer is measured in listening time, not book time`() {
        val atNormal = SleepTimer.stopPositionSec(
            SleepMode.Duration(15), chapters, positionSec = 0.0, armedAtPositionSec = 0.0, speed = 1.0f,
        )
        val atDouble = SleepTimer.stopPositionSec(
            SleepMode.Duration(15), chapters, positionSec = 0.0, armedAtPositionSec = 0.0, speed = 2.0f,
        )

        assertThat(atNormal).isEqualTo(900.0)
        assertThat(atDouble).isEqualTo(1_800.0)
    }

    @Test
    fun `end of chapter stops at the current chapter boundary`() {
        val stop = SleepTimer.stopPositionSec(
            SleepMode.EndOfChapter, chapters, positionSec = 700.0, armedAtPositionSec = 700.0,
        )
        assertThat(stop).isEqualTo(1_200.0)
    }

    /**
     * The bug this design exists to avoid: resolving the boundary once at arm time
     * means skipping a chapter fires the timer at the old chapter's end.
     */
    @Test
    fun `end of chapter follows a chapter skip instead of firing early`() {
        val armedAt = 100.0

        val beforeSkip = SleepTimer.stopPositionSec(
            SleepMode.EndOfChapter, chapters, positionSec = 100.0, armedAtPositionSec = armedAt,
        )
        // The listener skips into chapter three.
        val afterSkip = SleepTimer.stopPositionSec(
            SleepMode.EndOfChapter, chapters, positionSec = 1_250.0, armedAtPositionSec = armedAt,
        )

        assertThat(beforeSkip).isEqualTo(600.0)
        assertThat(afterSkip).isEqualTo(1_800.0)
    }

    @Test
    fun `expiry is reported only once the position reaches the stop point`() {
        fun expired(at: Double) = SleepTimer.hasExpired(
            SleepMode.Duration(10), chapters, positionSec = at, armedAtPositionSec = 0.0,
        )

        assertThat(expired(599.0)).isFalse()
        assertThat(expired(600.0)).isTrue()
        assertThat(expired(650.0)).isTrue()
    }

    @Test
    fun `no mode means no timer`() {
        assertThat(SleepTimer.remainingSec(null, chapters, 100.0, 0.0)).isNull()
        assertThat(SleepTimer.hasExpired(null, chapters, 100.0, 0.0)).isFalse()
        assertThat(SleepTimer.fadeVolume(null)).isEqualTo(1.0f)
    }

    @Test
    fun `volume fades to silence over the closing seconds`() {
        assertThat(SleepTimer.fadeVolume(60.0)).isEqualTo(1.0f)
        assertThat(SleepTimer.fadeVolume(20.0)).isEqualTo(1.0f)
        assertThat(SleepTimer.fadeVolume(10.0)).isWithin(0.01f).of(0.5f)
        assertThat(SleepTimer.fadeVolume(0.0)).isEqualTo(0.0f)
    }

    @Test
    fun `fade volume never leaves the audible range`() {
        var remaining = -50.0
        while (remaining < 200.0) {
            val volume = SleepTimer.fadeVolume(remaining)
            assertThat(volume).isAtLeast(0.0f)
            assertThat(volume).isAtMost(1.0f)
            remaining += 0.5
        }
    }

    @Test
    fun `extending adds to a running duration and starts one otherwise`() {
        assertThat(SleepTimer.extend(SleepMode.Duration(10), byMinutes = 5))
            .isEqualTo(SleepMode.Duration(15))
        assertThat(SleepTimer.extend(null, byMinutes = 5)).isEqualTo(SleepMode.Duration(5))
        assertThat(SleepTimer.extend(SleepMode.EndOfChapter, byMinutes = 5))
            .isEqualTo(SleepMode.Duration(5))
    }

    @Test
    fun `an item without chapters cannot arm end of chapter`() {
        assertThat(
            SleepTimer.stopPositionSec(SleepMode.EndOfChapter, emptyList(), 100.0, 100.0),
        ).isNull()
    }

    @Test
    fun `a chapter count stops at the end of the last one asked for`() {
        // Two chapters from part-way through the first means the end of the second.
        assertThat(
            SleepTimer.stopPositionSec(
                SleepMode.Chapters(2),
                chapters,
                positionSec = 300.0,
                armedAtPositionSec = 300.0,
            ),
        ).isEqualTo(1_200.0)

        // One chapter is the same question end-of-chapter answers.
        assertThat(
            SleepTimer.stopPositionSec(
                SleepMode.Chapters(1),
                chapters,
                positionSec = 300.0,
                armedAtPositionSec = 300.0,
            ),
        ).isEqualTo(600.0)
    }

    /**
     * The bug this pins down: resolving a chapter count from the *current* position makes
     * the target recede at exactly the speed it is approached. Playing from the first
     * chapter into the second would push a two-chapter timer from the end of chapter two
     * to the end of chapter three, and so on until the book ran out of chapters — a timer
     * that appears to do nothing at all.
     *
     * An earlier version of this test asserted precisely that receding behaviour and
     * called it "skipping shortens the count", which it never distinguished from ordinary
     * playback. It is fixed by counting from where the timer was armed.
     */
    @Test
    fun `a chapter count does not move as the book plays into it`() {
        val armedAt = 300.0

        val whenArmed = SleepTimer.stopPositionSec(SleepMode.Chapters(2), chapters, 300.0, armedAt)
        val oneChapterLater = SleepTimer.stopPositionSec(SleepMode.Chapters(2), chapters, 900.0, armedAt)
        val almostThere = SleepTimer.stopPositionSec(SleepMode.Chapters(2), chapters, 1_199.0, armedAt)

        assertThat(whenArmed).isEqualTo(1_200.0)
        assertThat(oneChapterLater).isEqualTo(1_200.0)
        assertThat(almostThere).isEqualTo(1_200.0)
    }

    @Test
    fun `a chapter count runs down as the book plays`() {
        val armedAt = 300.0
        val first = SleepTimer.remainingSec(SleepMode.Chapters(2), chapters, 300.0, armedAt)
        val later = SleepTimer.remainingSec(SleepMode.Chapters(2), chapters, 900.0, armedAt)

        assertThat(first).isEqualTo(900.0)
        assertThat(later).isEqualTo(300.0)
        assertThat(SleepTimer.hasExpired(SleepMode.Chapters(2), chapters, 1_200.0, armedAt)).isTrue()
    }

    @Test
    fun `asking for more chapters than remain stops at the end`() {
        assertThat(
            SleepTimer.stopPositionSec(
                SleepMode.Chapters(5),
                chapters,
                positionSec = 1_300.0,
                armedAtPositionSec = 1_300.0,
            ),
        ).isEqualTo(1_800.0)
    }

    @Test
    fun `a chapter count needs chapters`() {
        assertThat(
            SleepTimer.stopPositionSec(SleepMode.Chapters(2), emptyList(), 100.0, 100.0),
        ).isNull()
    }
}
