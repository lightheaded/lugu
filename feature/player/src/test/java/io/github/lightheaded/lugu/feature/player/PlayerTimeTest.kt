package io.github.lightheaded.lugu.feature.player

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.sync.SleepSettings
import org.junit.Test

class PlayerTimeTest {

    @Test
    fun `an hour is only shown once there is one`() {
        assertThat(formatTime(65.0)).isEqualTo("1:05")
        assertThat(formatTime(3725.0)).isEqualTo("1:02:05")
    }

    @Test
    fun `a position before the start reads as the start`() {
        assertThat(formatTime(-10.0)).isEqualTo("0:00")
    }

    @Test
    fun `lengths carry units so they are not read as timestamps`() {
        assertThat(formatDurationLabel(45.0)).isEqualTo("45 s")
        assertThat(formatDurationLabel(600.0)).isEqualTo("10 min")
        assertThat(formatDurationLabel(3600.0)).isEqualTo("1 h")
        assertThat(formatDurationLabel(3900.0)).isEqualTo("1 h 5 min")
    }

    @Test
    fun `whole minutes are shown as minutes`() {
        assertThat(formatShortSeconds(30)).isEqualTo("30s")
        assertThat(formatShortSeconds(300)).isEqualTo("5 min")
        assertThat(formatShortSeconds(90)).isEqualTo("90s")
    }

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
