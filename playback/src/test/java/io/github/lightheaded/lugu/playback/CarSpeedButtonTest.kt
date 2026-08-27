package io.github.lightheaded.lugu.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.sync.SpeedSettings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The car's speed button, which a driver twice saw as an icon with no number on it.
 *
 * The first fix put the rate in the display name, which Android Auto never draws. The rate
 * now travels inside the icon. No test can look at a car, so this holds to the three choices
 * that decide what a car receives: the icon for a rate, the words of the label, and one
 * button for one rate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(UnstableApi::class)
class CarSpeedButtonTest {

    @Test
    fun `an icon prints the rate that is really in force`() {
        listOf(
            0.5f to CommandButton.ICON_PLAYBACK_SPEED_0_5,
            0.8f to CommandButton.ICON_PLAYBACK_SPEED_0_8,
            1.0f to CommandButton.ICON_PLAYBACK_SPEED_1_0,
            1.2f to CommandButton.ICON_PLAYBACK_SPEED_1_2,
            1.5f to CommandButton.ICON_PLAYBACK_SPEED_1_5,
            1.8f to CommandButton.ICON_PLAYBACK_SPEED_1_8,
            2.0f to CommandButton.ICON_PLAYBACK_SPEED_2_0,
        ).forEach { (speed, icon) ->
            assertThat(CarSpeedButton.iconFor(speed)).isEqualTo(icon)
        }
    }

    @Test
    fun `every default preset has an icon that prints it`() {
        SpeedSettings.DEFAULT_PRESETS.forEach { preset ->
            assertThat(CarSpeedButton.iconFor(preset))
                .isNotEqualTo(CommandButton.ICON_PLAYBACK_SPEED)
        }
    }

    @Test
    fun `a rate with no icon of its own gets the plain one, never a wrong number`() {
        listOf(0.55f, 1.35f, 2.5f, 3.5f).forEach { speed ->
            assertThat(CarSpeedButton.iconFor(speed))
                .isEqualTo(CommandButton.ICON_PLAYBACK_SPEED)
        }
    }

    @Test
    fun `float drift does not lose the icon`() {
        // Twenty presses of the faster button from 1.0 land here, not on 2.0.
        assertThat(CarSpeedButton.iconFor(1.9999990f))
            .isEqualTo(CommandButton.ICON_PLAYBACK_SPEED_2_0)
        assertThat(CarSpeedButton.iconFor(1.7999992f))
            .isEqualTo(CommandButton.ICON_PLAYBACK_SPEED_1_8)
    }

    @Test
    fun `the label names the rate with the project's one speed formatter`() {
        assertThat(CarSpeedButton.labelFor(1.2f)).isEqualTo("Speed 1.2x")
        assertThat(CarSpeedButton.labelFor(2.0f)).isEqualTo("Speed 2x")
        assertThat(CarSpeedButton.labelFor(1.9999990f)).isEqualTo("Speed 2x")
    }

    @Test
    fun `the button carries the label and the cycle command`() {
        val button = CarSpeedButton.buttonFor(1.5f)
        assertThat(button.displayName.toString()).isEqualTo("Speed 1.5x")
        assertThat(button.icon).isEqualTo(CommandButton.ICON_PLAYBACK_SPEED_1_5)
        assertThat(button.sessionCommand?.customAction)
            .isEqualTo(CarSpeedButton.COMMAND_SPEED_CYCLE)
        assertThat(button.slots.asList()).containsExactly(CommandButton.SLOT_OVERFLOW)
    }

    @Test
    fun `one rate makes one button`() {
        val first = CarSpeedButton.buttonFor(1.2f)
        assertThat(CarSpeedButton.buttonFor(1.2f)).isSameInstanceAs(first)
        // Drift is the same rate, so it must not make a second button.
        assertThat(CarSpeedButton.buttonFor(1.1999999f)).isSameInstanceAs(first)
        assertThat(CarSpeedButton.buttonFor(1.5f)).isNotSameInstanceAs(first)
    }
}
