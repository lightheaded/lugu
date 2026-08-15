package io.github.lightheaded.lugu.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The fade has two jobs: reach silence exactly when the timer expires, and be inaudible
 * as a change on the way there. The first is testable, and the second is the reason the
 * curve is not a straight line.
 */
class SleepFadeTest {

    @Test
    fun `full volume until the fade begins`() {
        assertThat(SleepFade.volumeFor(remainingSec = 60.0, fadeSeconds = 20)).isEqualTo(1.0f)
    }

    @Test
    fun `silence at the moment the timer expires`() {
        assertThat(SleepFade.volumeFor(remainingSec = 0.0, fadeSeconds = 20)).isEqualTo(0.0f)
    }

    @Test
    fun `no fade means no change right up to the end`() {
        assertThat(SleepFade.volumeFor(remainingSec = 0.1, fadeSeconds = 0)).isEqualTo(1.0f)
    }

    @Test
    fun `a timer that is not armed leaves the volume alone`() {
        assertThat(SleepFade.volumeFor(remainingSec = null, fadeSeconds = 20)).isEqualTo(1.0f)
    }

    @Test
    fun `the volume only ever falls as the time runs out`() {
        val samples = (0..20).map { SleepFade.volumeFor(it.toDouble(), fadeSeconds = 20) }

        samples.zipWithNext { earlier, later -> assertThat(later).isAtLeast(earlier) }
    }

    /**
     * The curve is deliberately below the straight line: perceived loudness compresses
     * amplitude, so a linear ramp is heard as nothing happening and then a cliff.
     */
    @Test
    fun `the halfway point is quieter than a straight line would be`() {
        val halfway = SleepFade.volumeFor(remainingSec = 10.0, fadeSeconds = 20)

        assertThat(halfway).isLessThan(0.5f)
        assertThat(halfway).isGreaterThan(0.0f)
    }

    @Test
    fun `a negative remainder cannot produce a negative volume`() {
        assertThat(SleepFade.volumeFor(remainingSec = -5.0, fadeSeconds = 20)).isEqualTo(0.0f)
    }
}
