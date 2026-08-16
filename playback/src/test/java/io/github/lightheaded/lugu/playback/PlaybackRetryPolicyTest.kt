package io.github.lightheaded.lugu.playback

import androidx.media3.common.PlaybackException
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A retry that never gives up is a battery bug, and one that retries the wrong failures
 * hides a real error behind three identical attempts. Both halves are tested here.
 */
class PlaybackRetryPolicyTest {

    private val policy = PlaybackRetryPolicy()

    @Test
    fun `a dropped connection is worth another attempt`() {
        val delay = policy.retryDelayMs(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, 0)

        assertThat(delay).isEqualTo(PlaybackRetryPolicy.BASE_DELAY_MS)
    }

    @Test
    fun `the wait doubles with each attempt`() {
        val first = policy.retryDelayMs(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, 0)
        val second = policy.retryDelayMs(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, 1)

        assertThat(second).isEqualTo(first!! * 2)
    }

    @Test
    fun `attempts are bounded`() {
        val delay = policy.retryDelayMs(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackRetryPolicy.MAX_ATTEMPTS,
        )

        assertThat(delay).isNull()
    }

    @Test
    fun `a missing file will not get better by asking again`() {
        val delay = policy.retryDelayMs(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, 0)

        assertThat(delay).isNull()
    }

    /** A rejected token and a moved file are the common ones, and both are permanent. */
    @Test
    fun `a bad http status is not retried`() {
        assertThat(policy.isTransient(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)).isFalse()
    }

    @Test
    fun `a decoding failure is not a network problem`() {
        assertThat(policy.isTransient(PlaybackException.ERROR_CODE_DECODING_FAILED)).isFalse()
    }

    /**
     * The bound is an argument rather than a constant — see the class documentation — so it
     * is pinned as the figure that argument is about: half a minute of trying, not seven
     * seconds, now that a returning network is caught separately and this ladder covers the
     * connection that never went away.
     */
    @Test
    fun `the whole ladder is about half a minute`() {
        val total = (0 until PlaybackRetryPolicy.MAX_ATTEMPTS).sumOf {
            policy.retryDelayMs(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, it) ?: 0L
        }

        assertThat(total).isEqualTo(31_000L)
    }

    @Test
    fun `the wait never runs away`() {
        val delay = policy.retryDelayMs(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, 2)

        assertThat(delay).isAtMost(PlaybackRetryPolicy.MAX_DELAY_MS)
    }
}
