package io.github.lightheaded.lugu.playback

import android.media.audiofx.LoudnessEnhancer
import io.github.lightheaded.lugu.core.sync.AudioSettings
import io.github.lightheaded.lugu.core.sync.PlaybackDiary
import io.github.lightheaded.lugu.core.sync.PlaybackEvent

/**
 * Extra gain for a recording that is too quiet to hear in a car.
 *
 * The platform's [LoudnessEnhancer] is bound to an audio session, not to a player, and
 * that is the whole difficulty. The session id changes whenever the audio sink is rebuilt
 * — a new item, a route change, offload being turned on — and an effect attached to the
 * old id silently does nothing. So the effect is rebuilt on every id change rather than
 * created once.
 *
 * Two other awkward parts are handled deliberately:
 *
 *  - **A boost of zero attaches nothing.** An effect with no gain still routes the audio
 *    through an extra processing stage, and there is no reason to pay for that to change
 *    nothing. Off means absent.
 *  - **Construction can fail.** Some devices have no effect engine, and some refuse the
 *    session while it is in use. A setting that is silently ignored is worse than one
 *    that was never offered, so a failure is written to the diary.
 */
internal class LoudnessBoost(private val diary: PlaybackDiary) {

    private var effect: LoudnessEnhancer? = null
    private var attachedSessionId = SESSION_UNSET
    private var appliedGainDb = 0

    /** The session that has already been reported as unusable, so it is reported once. */
    private var reportedFailureFor = SESSION_UNSET

    /**
     * Attaches, retunes, or removes the effect so it matches [boostDb] on
     * [audioSessionId]. Safe to call as often as the settings or the session change.
     */
    fun apply(audioSessionId: Int, boostDb: Int) {
        val wanted = boostDb.coerceIn(0, AudioSettings.MAX_BOOST_DB)

        if (wanted == 0 || audioSessionId == SESSION_UNSET) {
            release()
            return
        }

        if (attachedSessionId != audioSessionId) release()

        val existing = effect
        if (existing != null) {
            if (appliedGainDb != wanted) {
                runCatching { existing.setTargetGain(wanted * MILLIBELS_PER_DECIBEL) }
                    .onSuccess { appliedGainDb = wanted }
            }
            return
        }

        if (reportedFailureFor == audioSessionId) return

        val created = runCatching {
            LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(wanted * MILLIBELS_PER_DECIBEL)
                enabled = true
            }
        }.getOrElse { failure ->
            // Catching broadly is right here: the constructor is documented to throw
            // three unrelated runtime exceptions, and some devices add their own.
            reportedFailureFor = audioSessionId
            diary.record(
                PlaybackEvent.ERROR,
                "volume boost unavailable: ${failure.javaClass.simpleName} ${failure.message.orEmpty()}",
            )
            return
        }

        effect = created
        attachedSessionId = audioSessionId
        appliedGainDb = wanted
        reportedFailureFor = SESSION_UNSET
    }

    /** Detaches the effect. Called on settings change, session change and shutdown. */
    fun release() {
        effect?.let { runCatching { it.release() } }
        effect = null
        attachedSessionId = SESSION_UNSET
        appliedGainDb = 0
    }

    private companion object {
        /** Matches `C.AUDIO_SESSION_ID_UNSET`; zero is never a real session. */
        const val SESSION_UNSET = 0

        const val MILLIBELS_PER_DECIBEL = 100
    }
}
