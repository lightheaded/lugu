package io.github.lightheaded.lugu.playback

import androidx.media3.common.PlaybackException

/**
 * Whether a failed playback is worth trying again, and when.
 *
 * A phone loses its connection constantly — a lift, a tunnel, a handover between cells —
 * and ExoPlayer's answer to a read that fails is to go idle and stay there. Nothing then
 * restarts it, so a two-second dropout ends the listening session for good. That is the
 * single most likely explanation for "it stopped and I did not touch it" while streaming.
 *
 * Only errors that a later attempt could plausibly survive are retried. A missing file, a
 * rejected token or a codec the phone cannot decode will fail identically forever, and
 * retrying those would turn one honest failure into three and hide it behind a delay.
 *
 * The attempt count is bounded because an unbounded retry is a battery and data leak that
 * looks like a hang: a phone genuinely out of signal must be allowed to give up.
 */
class PlaybackRetryPolicy(
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val baseDelayMs: Long = BASE_DELAY_MS,
    private val maxDelayMs: Long = MAX_DELAY_MS,
) {
    /**
     * How long to wait before attempt number [attemptsAlreadyMade] + 1, or null when this
     * error should not be retried at all.
     *
     * The delay doubles each time, so a connection that is coming back costs one second
     * and one that is not costs seven in total rather than a busy loop.
     */
    fun retryDelayMs(errorCode: Int, attemptsAlreadyMade: Int): Long? {
        if (attemptsAlreadyMade >= maxAttempts) return null
        if (!isTransient(errorCode)) return null
        val delay = baseDelayMs shl attemptsAlreadyMade.coerceIn(0, EXPONENT_CAP)
        return delay.coerceAtMost(maxDelayMs)
    }

    /**
     * True for the failures that are about the network rather than about the media.
     *
     * A bad HTTP status is deliberately excluded: the common ones are 401 and 404, which
     * mean the token expired or the file moved, and neither improves by asking again.
     */
    fun isTransient(errorCode: Int): Boolean = errorCode in TRANSIENT_CODES

    companion object {
        /** Three attempts covers a tunnel; a fourth is a phone with no signal. */
        const val MAX_ATTEMPTS = 3
        const val BASE_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 8_000L

        private const val EXPONENT_CAP = 4

        private val TRANSIENT_CODES = setOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT,
        )
    }
}
