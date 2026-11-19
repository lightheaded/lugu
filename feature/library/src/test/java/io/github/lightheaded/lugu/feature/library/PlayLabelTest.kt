package io.github.lightheaded.lugu.feature.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The primary button's words, which must never offer "Resume" on a finished book. */
class PlayLabelTest {

    private val tenHours = 10 * 3600.0

    @Test
    fun `no progress plays`() {
        assertThat(playLabel(isFinished = false, positionSec = 0.0, durationSec = tenHours)).isEqualTo("Play")
    }

    @Test
    fun `a finished book plays again, wherever its position is`() {
        assertThat(playLabel(isFinished = true, positionSec = tenHours, durationSec = tenHours))
            .isEqualTo("Play again")
        assertThat(playLabel(isFinished = true, positionSec = 0.0, durationSec = tenHours))
            .isEqualTo("Play again")
    }

    @Test
    fun `progress resumes and says the time left`() {
        val position = tenHours - (8 * 3600.0 + 51 * 60.0)
        assertThat(playLabel(isFinished = false, positionSec = position, durationSec = tenHours))
            .isEqualTo("Resume · 8h 51m left")
    }

    @Test
    fun `no duration resumes without a time`() {
        assertThat(playLabel(isFinished = false, positionSec = 120.0, durationSec = 0.0)).isEqualTo("Resume")
    }
}
