package io.github.lightheaded.lugu.playback

import androidx.media3.common.Player

/**
 * Why the player left the playing state.
 *
 * Every one of these looks identical from the outside — the audio stops — which is
 * exactly why the difference has to be written down at the moment it happens.
 */
enum class StopCause {
    /** A transport command: the app, the notification, a car, a headset. */
    REQUESTED,

    /** The item ran out. */
    ENDED,

    /** The player failed, or was reset to idle. */
    FAILED,

    /** Something else took the audio, temporarily or for good. */
    FOCUS_LOST,

    /** The output the audio was going to went away. */
    ROUTE_LOST,

    /** lugu itself asked for the stop; the sleep timer is the only one so far. */
    INTERNAL,

    /** Nothing asked. The case the whole diary exists for. */
    UNEXPECTED,
}

/**
 * A suppression reason in words, because the number means nothing to whoever is reading
 * the diary six hours after the drive.
 */
fun suppressionReasonName(reason: Int): String = when (reason) {
    Player.PLAYBACK_SUPPRESSION_REASON_NONE -> "none"
    Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> "transient audio focus loss"
    Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT -> "unsuitable audio output"
    Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING -> "scrubbing"
    else -> "suppression reason $reason"
}

/** What was true about the player at the instant it stopped playing. */
data class StopSignals(
    val playbackState: Int,
    val playWhenReadyChangeReason: Int,
    val suppressionReason: Int,
    val hasError: Boolean,
)

/** A cause, and the words to put in the diary next to it. */
data class StopVerdict(val cause: StopCause, val detail: String?)

/**
 * Works out why playback stopped, from the evidence the player leaves behind.
 *
 * The evidence is read in order of how much it proves, strongest first, because several
 * signals can be true at once — a stop caused by an error also has a `playWhenReady`
 * reason attached from whatever happened before it.
 *
 * ### Why this is sound
 *
 * Media3 attaches a *reason* to every `playWhenReady` change, and that reason is set by
 * the code that caused it rather than inferred afterwards: a controller calling `pause()`
 * produces `USER_REQUEST`, ExoPlayer's own permanent focus loss produces
 * `AUDIO_FOCUS_LOSS`, its becoming-noisy handling produces `AUDIO_BECOMING_NOISY`, and a
 * finished item produces `END_OF_MEDIA_ITEM`. Transient focus loss and an unsuitable
 * output do not touch `playWhenReady` at all — they raise a suppression reason instead,
 * which is why that is checked separately. So a stop that matches none of them was
 * genuinely not asked for by anything that knows how to say so.
 *
 * The one thing the reasons cannot tell apart is lugu's own `pause()` from a listener's:
 * both are `USER_REQUEST`, because the player has no idea who called it. That is what
 * [declare] is for — the sleep timer says "this next stop is mine" immediately before
 * pausing, and the declaration is consumed by the next stop within [declarationWindowMs].
 *
 * ### Where it can still be fooled
 *
 *  - A declaration that is made and then not followed by a stop stays live for the
 *    window, so a genuinely unexpected stop inside those two seconds would be blamed on
 *    the sleep timer. Two seconds is short enough that this is a rounding error and long
 *    enough to survive a slow main thread.
 *  - The process being killed produces no callback at all, so nothing is classified. The
 *    evidence for that case is a diary that simply stops, followed by `process started` —
 *    absence is the signal, which is why the diary is written to a file.
 *  - If the platform stops the audio without telling ExoPlayer, the player still believes
 *    it is playing and no stop is observed. Nothing recorded here can catch that; the
 *    symptom would be a diary that shows playing and a listener who hears silence.
 *  - `REQUESTED` means a transport command arrived, not that a human pressed anything.
 *    Any app holding a media controller can send one.
 */
class StopAttributor(
    private val declarationWindowMs: Long = DECLARATION_WINDOW_MS,
) {
    private var declaredReason: String? = null
    private var declaredAtMs = 0L

    /** Called immediately before lugu stops the player itself, naming the reason. */
    fun declare(reason: String, nowMs: Long) {
        declaredReason = reason
        declaredAtMs = nowMs
    }

    /** Drops any live declaration, for a stop that turned out not to happen. */
    fun forget() {
        declaredReason = null
    }

    /**
     * Classifies one stop, consuming any live declaration so it cannot explain a second
     * stop as well.
     */
    fun classify(signals: StopSignals, nowMs: Long): StopVerdict {
        val declared = declaredReason?.takeIf { nowMs - declaredAtMs <= declarationWindowMs }
        declaredReason = null

        return when {
            signals.hasError -> StopVerdict(StopCause.FAILED, "player error")

            signals.playbackState == Player.STATE_IDLE ->
                StopVerdict(StopCause.FAILED, declared ?: "player went idle")

            signals.playbackState == Player.STATE_ENDED ||
                signals.playWhenReadyChangeReason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM ->
                StopVerdict(StopCause.ENDED, null)

            signals.suppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS ->
                StopVerdict(StopCause.FOCUS_LOST, "transient audio focus loss")

            signals.suppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE ->
                StopVerdict(StopCause.ROUTE_LOST, suppressionReasonName(signals.suppressionReason))

            signals.playWhenReadyChangeReason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ->
                StopVerdict(StopCause.FOCUS_LOST, "audio focus lost to another app")

            signals.playWhenReadyChangeReason == Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG ->
                StopVerdict(StopCause.FOCUS_LOST, "suppressed for too long")

            signals.playWhenReadyChangeReason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ->
                StopVerdict(StopCause.ROUTE_LOST, "audio output disconnected")

            declared != null -> StopVerdict(StopCause.INTERNAL, declared)

            signals.playWhenReadyChangeReason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ->
                StopVerdict(StopCause.REQUESTED, null)

            signals.playWhenReadyChangeReason == Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE ->
                StopVerdict(StopCause.REQUESTED, "from a remote controller")

            else -> StopVerdict(StopCause.UNEXPECTED, "no reason reported")
        }
    }

    companion object {
        /**
         * No `playWhenReady` change explains this stop.
         *
         * Media3 has no constant for it, because from its point of view it does not
         * happen — but a player that stops while still wanting to play never reported a
         * reason, and that absence is precisely the evidence for an unexpected stop.
         */
        const val REASON_UNREPORTED = -1

        /** Reasons lugu declares for itself, named once so the diary stays searchable. */
        const val REASON_SLEEP_TIMER = "sleep timer"
        const val REASON_ROUTE_LOST = "audio route lost"
        const val REASON_STOP_COMMAND = "stop command"

        /**
         * How long a declaration explains the next stop.
         *
         * Long enough to survive a main thread busy enough to delay a callback, short
         * enough that a stale declaration cannot absorb a later, real one.
         */
        const val DECLARATION_WINDOW_MS = 2_000L
    }
}
