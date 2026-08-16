package io.github.lightheaded.lugu.feature.player

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.sync.SleepSettings
import org.junit.Test

/**
 * Writing a time down is pinned in `:core:model` now, alongside every other place lugu
 * prints a clock or a length. What is left here is what only the player can answer.
 */
class PlayerTimeTest {

    @Test
    fun `faster listening reaches an audio position in less of the listener's time`() {
        assertThat(wallClockSecondsAt(3600.0, 1.0f)).isWithin(0.01).of(3600.0)
        assertThat(wallClockSecondsAt(3600.0, 1.5f)).isWithin(0.01).of(2400.0)
        assertThat(wallClockSecondsAt(3600.0, 2.0f)).isWithin(0.01).of(1800.0)
    }

    @Test
    fun `a speed of zero cannot divide the clock away`() {
        assertThat(wallClockSecondsAt(60.0, 0f)).isFinite()
    }

    @Test
    fun `the sleep explanation covers both halves being off`() {
        assertThat(sleepExplanation(SleepSettings(fadeSeconds = 20, rewindOnWakeSec = 30)))
            .isEqualTo("Fades out over 20s, and rewinds 30s when you come back")
        assertThat(sleepExplanation(SleepSettings(fadeSeconds = 0, rewindOnWakeSec = 0)))
            .isEqualTo("Stops without fading, and starts again exactly where it stopped")
        assertThat(sleepExplanation(SleepSettings(fadeSeconds = 0, rewindOnWakeSec = 300)))
            .isEqualTo("Stops without fading, and rewinds 5 min when you come back")
    }
}
