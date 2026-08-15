package io.github.lightheaded.lugu.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A shake has to be deliberate and to count once.
 *
 * The failure that matters is not a missed shake — someone awake enough to shake a phone
 * will shake it again — but a shake that fires on every sample of one gesture and quietly
 * adds half an hour to the sleep timer.
 */
class ShakeGestureTest {

    @Test
    fun `a phone lying still is not a shake`() {
        val gesture = ShakeGesture(ShakeGesture.thresholdFor(2))

        assertThat(gesture.onSample(ShakeGesture.GRAVITY_MS2, atMs = 0)).isFalse()
    }

    @Test
    fun `a firm shake registers`() {
        val gesture = ShakeGesture(ShakeGesture.thresholdFor(2))

        assertThat(gesture.onSample(ShakeGesture.GRAVITY_MS2 + 12f, atMs = 0)).isTrue()
    }

    @Test
    fun `one gesture is one shake`() {
        val gesture = ShakeGesture(ShakeGesture.thresholdFor(2))
        val violent = ShakeGesture.GRAVITY_MS2 + 15f

        gesture.onSample(violent, atMs = 0)
        val duringSameGesture = (1..10).map { gesture.onSample(violent, atMs = it * 50L) }

        assertThat(duringSameGesture).doesNotContain(true)
    }

    @Test
    fun `a second shake after the cooldown counts again`() {
        val gesture = ShakeGesture(ShakeGesture.thresholdFor(2))
        val violent = ShakeGesture.GRAVITY_MS2 + 15f
        gesture.onSample(violent, atMs = 0)

        val later = gesture.onSample(violent, atMs = ShakeGesture.COOLDOWN_MS + 1)

        assertThat(later).isTrue()
    }

    @Test
    fun `a higher sensitivity setting takes less of a shake`() {
        val deliberate = ShakeGesture.thresholdFor(1)
        val nudge = ShakeGesture.thresholdFor(3)

        assertThat(nudge).isLessThan(deliberate)
    }

    @Test
    fun `a sensitivity outside the offered range still gives a usable threshold`() {
        assertThat(ShakeGesture.thresholdFor(0)).isEqualTo(ShakeGesture.thresholdFor(1))
        assertThat(ShakeGesture.thresholdFor(9)).isEqualTo(ShakeGesture.thresholdFor(3))
    }

    /** Turning over in bed must not buy another half hour of book. */
    @Test
    fun `an ordinary movement is below every threshold`() {
        val gentle = ShakeGesture.GRAVITY_MS2 + 3f

        (1..3).forEach { sensitivity ->
            val gesture = ShakeGesture(ShakeGesture.thresholdFor(sensitivity))
            assertThat(gesture.onSample(gentle, atMs = 0)).isFalse()
        }
    }
}
