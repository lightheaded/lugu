package io.github.lightheaded.lugu.playback

import android.media.AudioDeviceInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Resuming in a car and resuming for headphones are separate settings, so the two have to
 * be told apart from the device alone — and Bluetooth reports them identically. Car mode
 * is the tie-breaker, and these tests fix what it decides.
 */
class AudioRoutesTest {

    @Test
    fun `wired headphones are headphones`() {
        val routeClass = AudioRoutes.classify(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, isCarMode = false)

        assertThat(routeClass).isEqualTo(AudioRouteClass.HEADPHONES)
    }

    /** A cable in a car is still a cable; the car setting is about a car connecting. */
    @Test
    fun `wired output is never a car`() {
        val routeClass = AudioRoutes.classify(AudioDeviceInfo.TYPE_WIRED_HEADSET, isCarMode = true)

        assertThat(routeClass).isEqualTo(AudioRouteClass.HEADPHONES)
    }

    @Test
    fun `bluetooth audio is headphones outside a car`() {
        val routeClass = AudioRoutes.classify(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, isCarMode = false)

        assertThat(routeClass).isEqualTo(AudioRouteClass.HEADPHONES)
    }

    @Test
    fun `the same bluetooth profile is a car in car mode`() {
        val routeClass = AudioRoutes.classify(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, isCarMode = true)

        assertThat(routeClass).isEqualTo(AudioRouteClass.CAR)
    }

    @Test
    fun `a handsfree profile in car mode is a car`() {
        val routeClass = AudioRoutes.classify(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, isCarMode = true)

        assertThat(routeClass).isEqualTo(AudioRouteClass.CAR)
    }

    /** The speaker becoming available is not a reason to start a book playing out loud. */
    @Test
    fun `the phone speaker is nothing to resume for`() {
        val routeClass = AudioRoutes.classify(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, isCarMode = true)

        assertThat(routeClass).isEqualTo(AudioRouteClass.OTHER)
    }
}
