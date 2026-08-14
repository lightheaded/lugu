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
}
