package io.github.lightheaded.lugu.playback

/**
 * Why the next seek happens, written down by whatever asks for it.
 *
 * ExoPlayer reports every seek with one reason, `DISCONTINUITY_REASON_SEEK`, so the listener
 * that records the position history cannot tell a skip button from a chapter button from a
 * drag of the scrubber. They were all recorded as "seek". A trim that eats audio, or a
 * notification button that jumps too far, is then hard to find in that history.
 *
 * So each place that seeks for a reason calls [expect] first, with the position it asks
 * for. The listener calls [reasonFor] with the position the player landed on. The note is
 * used only when that position matches and the note is recent, so a seek from a surface
 * that wrote nothing — a car's scrubber, another app's controls — is never given somebody
 * else's reason. Without a match, the answer is [SEEK].
 *
 * One per process, held by [PlaybackStateHolder]: the app's controls are in the app and
 * the notification's are in the service, but they all seek the one player.
 */
internal class SeekReasons(private val nowMs: () -> Long = System::currentTimeMillis) {

    private data class Note(val reason: String, val targetSec: Double, val atMs: Long)

    @Volatile private var note: Note? = null

    /** The next seek to [targetSec], whole-item seconds, is for [reason]. */
    fun expect(reason: String, targetSec: Double) {
        note = Note(reason, targetSec.coerceAtLeast(0.0), nowMs())
    }

    /**
     * The reason for a seek that landed on [landedSec], and the note is spent.
     *
     * A note that does not match is kept until it is too old, because the seek it was
     * written for can still be on its way while an unrelated one lands first.
     */
    fun reasonFor(landedSec: Double): String {
        val current = note ?: return SEEK
        if (nowMs() - current.atMs > WINDOW_MS) {
            note = null
            return SEEK
        }
        if (kotlin.math.abs(landedSec - current.targetSec) > TOLERANCE_SEC) return SEEK
        note = null
        return current.reason
    }

    companion object {
        /**
         * The reasons written into the position history. Stable strings: they are stored,
         * and read back by the history screen.
         */
        const val SEEK = "seek"
        const val SKIP_BACK = "skip-back"
        const val SKIP_FORWARD = "skip-forward"
        const val CHAPTER_PREVIOUS = "chapter-previous"
        const val CHAPTER_NEXT = "chapter-next"

        /** A stretch the listener's trim settings jump over. */
        const val SKIPPED_REGION = "skipped-region"

        /** The rewind after a pause, or after the sleep timer. */
        const val REWIND_ON_RESUME = "rewind-on-resume"

        /** A position picked from the history, or the undo on a jump notice. */
        const val RESTORE = "restore"

        /**
         * How long a note waits for its seek. A seek from the app reaches the service
         * through the media controller, which takes milliseconds, not seconds.
         */
        const val WINDOW_MS = 3_000L

        /**
         * How far the landing may be from the target. Seeks are exact, so this covers
         * rounding to milliseconds and the move to a track boundary, not a real difference.
         */
        const val TOLERANCE_SEC = 1.0
    }
}
