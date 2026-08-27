package io.github.lightheaded.lugu.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Why the sleep timer must put the volume back, stated as an assertion.
 *
 * The countdown arithmetic is proved in `SleepChaptersTest`, and the shape of the curve in
 * `SleepFadeTest`. Neither says what volume the player holds at the tick that stops the
 * book. That number is the whole defect: the service writes the fade volume to the player
 * on every tick, so the last value it writes before the stop is the value the player keeps.
 * If nothing writes 1.0 back, the player stays silent, and the next play makes no sound.
 *
 * The restore itself is not reachable here. It is four separate lines inside private methods
 * of `LuguPlaybackService`, which is a `MediaLibraryService` and needs a device. The report
 * for this change names the seam that closes that.
 */
class SleepTimerRestoreTest {

    /** The service ticks the sleep timer every 500 ms. See `LuguPlaybackService`. */
    private val tickMs = 500L

    /**
     * The volume of every tick from the start of the fade to the stop, in order.
     *
     * This is the service loop with the player taken out: remaining time falls by one tick
     * of content, the fade turns that into a volume, and the countdown says when to stop.
     */
    private fun fadeRun(fadeSeconds: Int, speed: Float): List<Float> {
        val stepSec = tickMs / 1000.0 * speed
        var remaining = fadeSeconds.toDouble() + 5.0
        val volumes = mutableListOf(SleepFade.volumeFor(remaining, fadeSeconds))
        while (!SleepCountdown.isDue(remaining, tickMs, speed)) {
            remaining -= stepSec
            volumes += SleepFade.volumeFor(remaining, fadeSeconds)
        }
        return volumes
    }

    /**
     * The defect this class exists for. A twenty second fade is silent at the stop, so the
     * player is left silent unless the service restores it.
     */
    @Test
    fun `the default fade is silent at the tick that stops the book`() {
        val volumeAtStop = fadeRun(fadeSeconds = 20, speed = 1.0f).last()

        assertThat(volumeAtStop).isAtMost(0.001f)
        assertThat(volumeAtStop).isAtLeast(0.0f)
    }

    /** A faster book crosses the fade in fewer ticks, and still arrives at silence. */
    @Test
    fun `a fade at three times speed is also silent at the stop`() {
        assertThat(fadeRun(fadeSeconds = 20, speed = 3.0f).last()).isAtMost(0.01f)
    }

    /**
     * "No fade" means an abrupt stop, so there is nothing to restore. The restore must
     * still be safe here, because the volume it writes is the volume already in place.
     */
    @Test
    fun `no fade stops the book at full volume`() {
        assertThat(fadeRun(fadeSeconds = 0, speed = 1.0f).last()).isEqualTo(1.0f)
    }

    /**
     * A fade of one second is shorter than a few ticks, so the curve cannot get near
     * silence before the stop. The book stops while it can still be heard, which is the
     * honest reading of a one second fade, and it is recorded here so a later change to
     * the curve cannot make it a surprise.
     */
    @Test
    fun `a one second fade stops the book while it can still be heard`() {
        assertThat(fadeRun(fadeSeconds = 1, speed = 1.0f).last()).isGreaterThan(0.1f)
    }

    /**
     * The point of the curve is that no single tick is heard as a change. A step of more
     * than a tenth of full volume between two ticks is an audible drop.
     */
    @Test
    fun `no single tick of the default fade drops the volume audibly`() {
        val steps = fadeRun(fadeSeconds = 20, speed = 1.0f).zipWithNext { earlier, later -> earlier - later }
        val worst = steps.maxOrNull() ?: 0.0f

        assertThat(worst).isLessThan(0.1f)
    }

    /** The fade spends many ticks on the way down, rather than one long silence. */
    @Test
    fun `the default fade is spread over many ticks`() {
        val faded = fadeRun(fadeSeconds = 20, speed = 1.0f).count { it < 1.0f && it > 0.0f }

        assertThat(faded).isAtLeast(30)
    }

    /**
     * The whole loop, as the service runs it, with the player replaced by a list of writes.
     *
     * Every write the service makes to `player.volume`, in order, for one armed timer. The
     * last entry is what the player keeps, and it is the value that decides whether the next
     * play makes a sound.
     */
    private fun volumeWrites(fadeSeconds: Int, speed: Float): List<Float> {
        val stepSec = tickMs / 1000.0 * speed
        var remaining = fadeSeconds.toDouble() + 5.0
        val writes = mutableListOf<Float>()
        while (true) {
            val tick = sleepTickFor(remaining, fadeSeconds, tickMs, speed)
            writes += tick.volume
            if (tick.stops) {
                writes += tick.volumeAfterStop
                return writes
            }
            remaining -= stepSec
        }
    }

    /**
     * The assertion that saves the next morning.
     *
     * The fade reaches silence, the book stops, and the last thing written to the player is
     * full volume. Without that last write the player stays where the fade left it, and the
     * next play is silent with nothing on screen to explain it.
     */
    @Test
    fun `the last volume written after a stop is full volume`() {
        val writes = volumeWrites(fadeSeconds = 20, speed = 1.0f)

        assertThat(writes.last()).isEqualTo(1.0f)
        assertThat(writes[writes.size - 2]).isAtMost(0.001f)
    }

    /** The same holds at every fade length the settings screen offers, and at any speed. */
    @Test
    fun `every fade length and speed ends at full volume`() {
        for (fade in listOf(0, 1, 5, 10, 20, 30, 60)) {
            for (speed in listOf(0.5f, 1.0f, 1.8f, 3.0f)) {
                val writes = volumeWrites(fade, speed)
                assertThat(writes.last()).isEqualTo(1.0f)
            }
        }
    }

    /** A tick that does not stop the book must never claim the volume of one that does. */
    @Test
    fun `a tick that does not stop the book leaves the fade volume in place`() {
        val tick = sleepTickFor(remainingSec = 10.0, fadeSeconds = 20, tickMs = tickMs, speed = 1.0f)

        assertThat(tick.stops).isFalse()
        assertThat(tick.volume).isLessThan(1.0f)
        assertThat(tick.isFading).isTrue()
    }

    /** Outside the fade the timer writes full volume, and says it is not fading. */
    @Test
    fun `before the fade begins the timer writes full volume`() {
        val tick = sleepTickFor(remainingSec = 300.0, fadeSeconds = 20, tickMs = tickMs, speed = 1.0f)

        assertThat(tick.volume).isEqualTo(1.0f)
        assertThat(tick.isFading).isFalse()
        assertThat(tick.stops).isFalse()
    }
}
