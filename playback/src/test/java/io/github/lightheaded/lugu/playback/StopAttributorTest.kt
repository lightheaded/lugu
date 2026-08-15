package io.github.lightheaded.lugu.playback

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The detector behind the one diary line that matters most.
 *
 * "It stopped and I do not know why" is only answerable if a stop that nothing asked for
 * is distinguishable from the five kinds that something did. These tests are that
 * distinction, written down: every ordinary cause must be attributed to itself, and the
 * unexpected verdict must be reachable, or the record it produces means nothing.
 */
class StopAttributorTest {

    private fun signals(
        playbackState: Int = Player.STATE_READY,
        playWhenReadyChangeReason: Int = StopAttributor.REASON_UNREPORTED,
        suppressionReason: Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
        hasError: Boolean = false,
    ) = StopSignals(playbackState, playWhenReadyChangeReason, suppressionReason, hasError)

    @Test
    fun `a stop with nothing to explain it is unexpected`() {
        val attributor = StopAttributor()

        val verdict = attributor.classify(signals(), NOW)

        assertThat(verdict.cause).isEqualTo(StopCause.UNEXPECTED)
    }

    @Test
    fun `a transport command is a requested stop`() {
        val attributor = StopAttributor()

        val verdict = attributor.classify(
            signals(playWhenReadyChangeReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST),
            NOW,
        )

        assertThat(verdict.cause).isEqualTo(StopCause.REQUESTED)
    }

    @Test
    fun `a declared stop outranks the user request it produces`() {
        val attributor = StopAttributor()
        attributor.declare(StopAttributor.REASON_SLEEP_TIMER, NOW)

        val verdict = attributor.classify(
            signals(playWhenReadyChangeReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST),
            NOW,
        )

        assertThat(verdict.cause).isEqualTo(StopCause.INTERNAL)
        assertThat(verdict.detail).isEqualTo(StopAttributor.REASON_SLEEP_TIMER)
    }

    @Test
    fun `a declaration explains one stop only`() {
        val attributor = StopAttributor()
        attributor.declare(StopAttributor.REASON_SLEEP_TIMER, NOW)
        attributor.classify(signals(), NOW)

        val second = attributor.classify(signals(), NOW)

        assertThat(second.cause).isEqualTo(StopCause.UNEXPECTED)
    }

    @Test
    fun `a stale declaration explains nothing`() {
        val attributor = StopAttributor()
        attributor.declare(StopAttributor.REASON_SLEEP_TIMER, NOW)

        val verdict = attributor.classify(signals(), NOW + StopAttributor.DECLARATION_WINDOW_MS + 1)

        assertThat(verdict.cause).isEqualTo(StopCause.UNEXPECTED)
    }

    @Test
    fun `transient focus loss is a focus loss rather than an unexpected stop`() {
        val attributor = StopAttributor()

        val verdict = attributor.classify(
            signals(suppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS),
            NOW,
        )

        assertThat(verdict.cause).isEqualTo(StopCause.FOCUS_LOST)
    }

    @Test
    fun `permanent focus loss is a focus loss`() {
        val attributor = StopAttributor()

        val verdict = attributor.classify(
            signals(playWhenReadyChangeReason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS),
            NOW,
        )

        assertThat(verdict.cause).isEqualTo(StopCause.FOCUS_LOST)
    }

    @Test
    fun `an unsuitable output is a lost route`() {
        val attributor = StopAttributor()

        val verdict = attributor.classify(
            signals(suppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT),
            NOW,
        )

        assertThat(verdict.cause).isEqualTo(StopCause.ROUTE_LOST)
    }

    @Test
    fun `headphones being pulled out is a lost route`() {
        val attributor = StopAttributor()

        val verdict = attributor.classify(
            signals(playWhenReadyChangeReason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY),
            NOW,
        )

        assertThat(verdict.cause).isEqualTo(StopCause.ROUTE_LOST)
    }

    @Test
    fun `an error outranks everything else that is true at the same moment`() {
        val attributor = StopAttributor()
        attributor.declare(StopAttributor.REASON_SLEEP_TIMER, NOW)

        val verdict = attributor.classify(
            signals(
                playbackState = Player.STATE_IDLE,
                playWhenReadyChangeReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                hasError = true,
            ),
            NOW,
        )

        assertThat(verdict.cause).isEqualTo(StopCause.FAILED)
    }

    @Test
    fun `the end of an item is not a stop worth investigating`() {
        val attributor = StopAttributor()

        val verdict = attributor.classify(signals(playbackState = Player.STATE_ENDED), NOW)

        assertThat(verdict.cause).isEqualTo(StopCause.ENDED)
    }

    @Test
    fun `a stop command leaves the player idle without looking like a failure`() {
        val attributor = StopAttributor()
        attributor.declare(StopAttributor.REASON_STOP_COMMAND, NOW)

        val verdict = attributor.classify(signals(playbackState = Player.STATE_IDLE), NOW)

        assertThat(verdict.cause).isEqualTo(StopCause.FAILED)
        assertThat(verdict.detail).isEqualTo(StopAttributor.REASON_STOP_COMMAND)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
