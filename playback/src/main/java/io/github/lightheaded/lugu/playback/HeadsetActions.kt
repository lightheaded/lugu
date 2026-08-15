package io.github.lightheaded.lugu.playback

import io.github.lightheaded.lugu.core.model.MediaIntent
import io.github.lightheaded.lugu.core.sync.HeadsetAction
import io.github.lightheaded.lugu.core.sync.HeadsetSettings

/** Which of a headset's two side buttons was pressed. */
enum class HeadsetDirection {
    PREVIOUS,
    NEXT,
}

/** A resolved press: which button it was, and what the listener asked that button to do. */
data class HeadsetPress(val direction: HeadsetDirection, val action: HeadsetAction)

/**
 * Turns a classified media-button press into the thing the listener asked for.
 *
 * Kept apart from the service so it can be tested without a player, and kept apart from
 * [io.github.lightheaded.lugu.core.model.MediaButtonClassifier] because the two answer
 * different questions. The classifier decides whether a press was real — it is what
 * removes the phantom rewinds caused by headsets that emit a pause and a play in the same
 * millisecond. This decides what a real press means, which is a setting rather than a fact
 * about the hardware.
 */
object HeadsetActions {

    /**
     * @return null when the press was not one of the two side buttons, or when the
     *   classifier rejected it as a duplicate or a glitch. Nothing should happen in either
     *   case, and a caller that cannot tell them apart would act on a press that was never
     *   made.
     */
    fun resolve(intent: MediaIntent, settings: HeadsetSettings): HeadsetPress? = when (intent) {
        MediaIntent.SkipForward -> HeadsetPress(HeadsetDirection.NEXT, settings.nextAction)
        MediaIntent.SkipBack -> HeadsetPress(HeadsetDirection.PREVIOUS, settings.previousAction)
        else -> null
    }
}
