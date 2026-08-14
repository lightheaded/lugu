package io.github.lightheaded.lugu.core.model

/** A media button press as it arrives from a headset, car or the notification. */
enum class MediaButton {
    PLAY,
    PAUSE,
    PLAY_PAUSE,
    NEXT,
    PREVIOUS,
}

/** What lugu decided the user actually meant. */
sealed interface MediaIntent {
    data object Play : MediaIntent

    data object Pause : MediaIntent

    data object SkipForward : MediaIntent

    data object SkipBack : MediaIntent

    /**
     * A pause immediately followed by a play — the headset stuttered rather than the
     * listener pausing. Playback continues and, crucially, no rewind is armed.
     */
    data object PauseGlitch : MediaIntent

    /** A repeat of an event already acted on. Dropped. */
    data object Duplicate : MediaIntent
}

/**
 * Classifies media-button sequences.
 *
 * Some Bluetooth headsets emit a PAUSE followed within milliseconds by a PLAY — on a
 * reconnect, an audio-focus blip, or simply a noisy button. Treating those as two real
 * events is the direct cause of the official app's phantom rewinds (app #1048): the
 * pause arms a rewind, the play applies it, and the position walks backwards a little
 * every time it happens.
 *
 * Taking the pair as one glitch removes the whole class of bug. Not thread-safe by
 * design — it is driven from the playback service's single callback thread.
 */
class MediaButtonClassifier(
    /** A PLAY arriving within this long after a PAUSE is treated as the same event. */
    private val glitchWindowMs: Long = 500,
    /** Identical events closer together than this are the same physical press. */
    private val duplicateWindowMs: Long = 60,
) {
    private var lastButton: MediaButton? = null
    private var lastAtMs: Long = Long.MIN_VALUE
    private var pausedAtMs: Long? = null

    /** True while playback is paused as far as the classifier is aware. */
    val isPaused: Boolean get() = pausedAtMs != null

    /** How long the current pause has lasted, or null when playing. */
    fun pausedForMs(nowMs: Long): Long? = pausedAtMs?.let { nowMs - it }

    fun classify(button: MediaButton, atMs: Long, isPlaying: Boolean): MediaIntent {
        val previous = lastButton
        val sincePrevious = atMs - lastAtMs

        val resolved = when (button) {
            MediaButton.PLAY_PAUSE -> if (isPlaying) MediaButton.PAUSE else MediaButton.PLAY
            else -> button
        }

        if (previous == resolved && sincePrevious in 0 until duplicateWindowMs) {
            lastAtMs = atMs
            return MediaIntent.Duplicate
        }

        val intent = when (resolved) {
            MediaButton.PAUSE -> {
                pausedAtMs = atMs
                MediaIntent.Pause
            }

            MediaButton.PLAY -> {
                val pausedAt = pausedAtMs
                pausedAtMs = null
                if (previous == MediaButton.PAUSE && pausedAt != null && atMs - pausedAt < glitchWindowMs) {
                    MediaIntent.PauseGlitch
                } else {
                    MediaIntent.Play
                }
            }

            MediaButton.NEXT -> MediaIntent.SkipForward
            MediaButton.PREVIOUS -> MediaIntent.SkipBack
            MediaButton.PLAY_PAUSE -> MediaIntent.Duplicate // Resolved above; unreachable.
        }

        lastButton = resolved
        lastAtMs = atMs
        return intent
    }

    /** Call when playback state changes for a reason other than a button. */
    fun onPlaybackStateChanged(isPlaying: Boolean, atMs: Long) {
        pausedAtMs = if (isPlaying) null else pausedAtMs ?: atMs
    }

    fun reset() {
        lastButton = null
        lastAtMs = Long.MIN_VALUE
        pausedAtMs = null
    }
}
