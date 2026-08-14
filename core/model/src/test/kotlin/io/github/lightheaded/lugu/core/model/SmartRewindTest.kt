package io.github.lightheaded.lugu.core.model

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

class SmartRewindTest {

    private val settings = SmartRewind.Settings()

    @Test
    fun `a brief pause rewinds nothing`() {
        assertThat(SmartRewind.rewindSeconds(0)).isEqualTo(0.0)
        assertThat(SmartRewind.rewindSeconds(5_000)).isEqualTo(0.0)
        assertThat(SmartRewind.rewindSeconds(29_999)).isEqualTo(0.0)
    }

    @Test
    fun `a long pause rewinds the full amount`() {
        assertThat(SmartRewind.rewindSeconds(3_600_000)).isEqualTo(settings.maxRewindSec)
        assertThat(SmartRewind.rewindSeconds(48 * 3_600_000L)).isEqualTo(settings.maxRewindSec)
    }

    @Test
    fun `a few minutes away costs a few seconds`() {
        val twoMinutes = SmartRewind.rewindSeconds(120_000)
        assertThat(twoMinutes).isGreaterThan(0.0)
        assertThat(twoMinutes).isLessThan(15.0)
    }

    /** Longer away must never mean less rewind — the curve has to be monotonic. */
    @Test
    fun `rewind never decreases as the pause lengthens`() {
        var previous = 0.0
        var pauseMs = 0L
        while (pauseMs < 8 * 3_600_000L) {
            val current = SmartRewind.rewindSeconds(pauseMs)
            assertThat(current).isAtLeast(previous)
            previous = current
            pauseMs += 5_000
        }
    }

    @Test
    fun `rewind stays within bounds for any input`() {
        val random = Random(seed = 99)
        repeat(5_000) {
            val pause = random.nextLong(-10_000, 100L * 3_600_000)
            val rewind = SmartRewind.rewindSeconds(pause)
            assertThat(rewind).isAtLeast(0.0)
            assertThat(rewind).isAtMost(settings.maxRewindSec)
        }
    }

    @Test
    fun `resuming near the start never seeks before zero`() {
        assertThat(SmartRewind.resumePosition(currentSec = 3.0, pausedForMs = 24 * 3_600_000L))
            .isEqualTo(0.0)
        assertThat(SmartRewind.resumePosition(currentSec = 0.0, pausedForMs = 24 * 3_600_000L))
            .isEqualTo(0.0)
    }

    @Test
    fun `disabling the feature rewinds nothing however long the pause`() {
        val off = SmartRewind.Settings(enabled = false)
        assertThat(SmartRewind.rewindSeconds(24 * 3_600_000L, off)).isEqualTo(0.0)
    }

    @Test
    fun `the hint stays quiet when nothing moved`() {
        assertThat(SmartRewind.describe(5_000)).isNull()
        assertThat(SmartRewind.describe(3_600_000)).isEqualTo("Rewound 30s")
    }

    @Test
    fun `settings reject a nonsensical range`() {
        runCatching { SmartRewind.Settings(minPauseSec = 60, fullRewindAfterSec = 30) }
            .also { assertThat(it.isFailure).isTrue() }
    }
}

class MediaButtonClassifierTest {

    @Test
    fun `a pause followed immediately by play is a glitch, not a real pause`() {
        val classifier = MediaButtonClassifier()

        assertThat(classifier.classify(MediaButton.PAUSE, atMs = 1_000, isPlaying = true))
            .isEqualTo(MediaIntent.Pause)
        // The headset stuttered; the listener never paused.
        assertThat(classifier.classify(MediaButton.PLAY, atMs = 1_120, isPlaying = false))
            .isEqualTo(MediaIntent.PauseGlitch)
    }

    @Test
    fun `a genuine pause and later resume is a real play`() {
        val classifier = MediaButtonClassifier()

        classifier.classify(MediaButton.PAUSE, atMs = 1_000, isPlaying = true)
        assertThat(classifier.classify(MediaButton.PLAY, atMs = 40_000, isPlaying = false))
            .isEqualTo(MediaIntent.Play)
    }

    @Test
    fun `a repeated identical event from one physical press is dropped`() {
        val classifier = MediaButtonClassifier()

        assertThat(classifier.classify(MediaButton.NEXT, atMs = 500, isPlaying = true))
            .isEqualTo(MediaIntent.SkipForward)
        assertThat(classifier.classify(MediaButton.NEXT, atMs = 520, isPlaying = true))
            .isEqualTo(MediaIntent.Duplicate)
        // Far enough apart to be a deliberate second press.
        assertThat(classifier.classify(MediaButton.NEXT, atMs = 1_200, isPlaying = true))
            .isEqualTo(MediaIntent.SkipForward)
    }

    @Test
    fun `play pause resolves against the current playback state`() {
        val classifier = MediaButtonClassifier()

        assertThat(classifier.classify(MediaButton.PLAY_PAUSE, atMs = 0, isPlaying = true))
            .isEqualTo(MediaIntent.Pause)
        assertThat(classifier.classify(MediaButton.PLAY_PAUSE, atMs = 30_000, isPlaying = false))
            .isEqualTo(MediaIntent.Play)
    }

    @Test
    fun `the classifier tracks how long the pause has lasted`() {
        val classifier = MediaButtonClassifier()

        assertThat(classifier.pausedForMs(1_000)).isNull()
        classifier.classify(MediaButton.PAUSE, atMs = 1_000, isPlaying = true)
        assertThat(classifier.isPaused).isTrue()
        assertThat(classifier.pausedForMs(61_000)).isEqualTo(60_000)

        classifier.classify(MediaButton.PLAY, atMs = 61_000, isPlaying = false)
        assertThat(classifier.isPaused).isFalse()
        assertThat(classifier.pausedForMs(62_000)).isNull()
    }

    /**
     * The bug this exists to prevent: a stream of glitch pairs must not accumulate any
     * rewind at all. Each pair is under the floor individually, and must also not be
     * mistaken for a long pause in aggregate.
     */
    @Test
    fun `a storm of glitch pairs rewinds nothing`() {
        val classifier = MediaButtonClassifier()
        var totalRewind = 0.0
        var now = 0L

        repeat(50) {
            classifier.classify(MediaButton.PAUSE, atMs = now, isPlaying = true)
            now += 100
            val intent = classifier.classify(MediaButton.PLAY, atMs = now, isPlaying = false)
            assertThat(intent).isEqualTo(MediaIntent.PauseGlitch)
            totalRewind += SmartRewind.rewindSeconds(100)
            now += 5_000
        }

        assertThat(totalRewind).isEqualTo(0.0)
    }
}
