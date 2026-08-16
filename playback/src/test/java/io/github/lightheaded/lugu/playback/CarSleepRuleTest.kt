package io.github.lightheaded.lugu.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A sleep timer must not fire on a motorway, and must still be there afterwards.
 *
 * Both halves matter equally. Holding the timer is the feature; giving it back is what
 * stops the feature from being a way of losing the setting.
 */
class CarSleepRuleTest {

    @Test
    fun `an armed timer is held when a car connects`() {
        assertThat(CarSleepRule.actionFor(inCar = true, armed = true, suspended = false))
            .isEqualTo(SleepTimerCarAction.SUSPEND)
    }

    @Test
    fun `a held timer is given back when the car goes`() {
        assertThat(CarSleepRule.actionFor(inCar = false, armed = true, suspended = true))
            .isEqualTo(SleepTimerCarAction.RELEASE)
    }

    /** Deciding the same thing twice a second must not write a line twice a second. */
    @Test
    fun `a timer already held is left alone`() {
        assertThat(CarSleepRule.actionFor(inCar = true, armed = true, suspended = true))
            .isEqualTo(SleepTimerCarAction.NOTHING)
    }

    @Test
    fun `a car with no timer set changes nothing`() {
        assertThat(CarSleepRule.actionFor(inCar = true, armed = false, suspended = false))
            .isEqualTo(SleepTimerCarAction.NOTHING)
        assertThat(CarSleepRule.actionFor(inCar = false, armed = false, suspended = false))
            .isEqualTo(SleepTimerCarAction.NOTHING)
    }

    /**
     * The timer can be cancelled during the journey. The hold has to come off with it, or
     * the next timer set after the drive would never count down.
     */
    @Test
    fun `a hold is released even when the timer went away during the journey`() {
        assertThat(CarSleepRule.actionFor(inCar = false, armed = false, suspended = true))
            .isEqualTo(SleepTimerCarAction.RELEASE)
    }

    /** Arming inside a car is not refused; it is held like any other, on the next decision. */
    @Test
    fun `a timer armed while driving is held rather than refused`() {
        assertThat(CarSleepRule.actionFor(inCar = true, armed = true, suspended = false))
            .isEqualTo(SleepTimerCarAction.SUSPEND)
    }
}
