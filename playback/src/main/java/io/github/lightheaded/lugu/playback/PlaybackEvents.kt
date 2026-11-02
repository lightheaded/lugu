package io.github.lightheaded.lugu.playback

/**
 * The plays and pauses written into the position history, beside the seeks of
 * [SeekReasons]. Stable strings: they are stored, and read back by the history sheet.
 *
 * A pause carries its cause when the player knows it, because "paused" alone does not
 * tell a listener why the book stopped at midnight.
 */
object PlaybackEvents {
    const val PLAY = "play"
    const val PAUSE = "pause"

    /** The headphones or the Bluetooth device went away. */
    const val PAUSE_NOISY = "pause-noisy"

    /** Another app took the audio, for example a call. */
    const val PAUSE_FOCUS = "pause-focus"

    /** The sleep timer ran out. */
    const val PAUSE_SLEEP_TIMER = "pause-sleep-timer"

    /** The item played to its end. */
    const val ENDED = "ended"

    private val ALL = setOf(PLAY, PAUSE, PAUSE_NOISY, PAUSE_FOCUS, PAUSE_SLEEP_TIMER, ENDED)

    /** True for a play or a pause, false for a jump. */
    fun isPlaybackEvent(reason: String): Boolean = reason in ALL

    /** The event for a change of `playWhenReady`, from Media3's reason for it. */
    fun forPlayWhenReady(playWhenReady: Boolean, changeReason: Int): String = when {
        playWhenReady -> PLAY
        changeReason == androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ->
            PAUSE_NOISY
        changeReason == androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ->
            PAUSE_FOCUS
        else -> PAUSE
    }
}
