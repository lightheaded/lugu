package io.github.lightheaded.lugu.core.sync

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.formatSpeedNumber
import org.junit.Test

/**
 * The fine adjustment beside the speed presets.
 *
 * [SpeedSettings.STEP] was declared and unused while the sheet added a hardcoded 0.05 to a
 * float on every press — which drifts, and which had no idea the range had ends.
 */
class SpeedStepTest {

    @Test
    fun `one press moves by one step`() {
        assertThat(SpeedSettings.stepped(1.5f, 1)).isEqualTo(1.55f)
        assertThat(SpeedSettings.stepped(1.5f, -1)).isEqualTo(1.45f)
    }

    @Test
    fun `stepping repeatedly does not drift off the grid`() {
        var speed = 1.0f
        repeat(15) { speed = SpeedSettings.stepped(speed, 1) }

        // Adding 0.05f fifteen times in a float lands on 1.7499998, which is not a step
        // away from anything and formats as a number nobody chose.
        assertThat(speed).isEqualTo(1.75f)
        assertThat(formatSpeedNumber(speed)).isEqualTo("1.75")
    }

    @Test
    fun `a preset stepped and a preset picked are the same number`() {
        assertThat(SpeedSettings.stepped(1.2f, 12)).isEqualTo(SpeedSettings.stepped(1.8f, 0))
    }

    @Test
    fun `the ends hold`() {
        assertThat(SpeedSettings.stepped(SpeedSettings.MIN, -1)).isEqualTo(SpeedSettings.MIN)
        assertThat(SpeedSettings.stepped(SpeedSettings.MAX, 1)).isEqualTo(SpeedSettings.MAX)
        // Which is what lets the buttons switch themselves off rather than accepting a
        // press that changes nothing.
        assertThat(SpeedSettings.stepped(0.52f, -1)).isEqualTo(SpeedSettings.MIN)
    }
}
