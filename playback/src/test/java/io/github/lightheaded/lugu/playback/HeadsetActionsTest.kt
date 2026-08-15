package io.github.lightheaded.lugu.playback

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.MediaButton
import io.github.lightheaded.lugu.core.model.MediaButtonClassifier
import io.github.lightheaded.lugu.core.model.MediaIntent
import io.github.lightheaded.lugu.core.sync.HeadsetAction
import io.github.lightheaded.lugu.core.sync.HeadsetSettings
import org.junit.Test

/**
 * What a headset's side buttons do, which used to be Media3's decision and is now the
 * listener's. The stock answer was `seekToPrevious()`, which on a single-file audiobook
 * seeks to zero — one press, forty hours.
 */
class HeadsetActionsTest {

    private val skipping = HeadsetSettings()

    @Test
    fun `the next button follows the next setting and the previous button the previous one`() {
        val settings = HeadsetSettings(
            nextAction = HeadsetAction.ITEM,
            previousAction = HeadsetAction.CHAPTER,
        )

        val next = HeadsetActions.resolve(MediaIntent.SkipForward, settings)
        val previous = HeadsetActions.resolve(MediaIntent.SkipBack, settings)

        assertThat(next).isEqualTo(HeadsetPress(HeadsetDirection.NEXT, HeadsetAction.ITEM))
        assertThat(previous).isEqualTo(HeadsetPress(HeadsetDirection.PREVIOUS, HeadsetAction.CHAPTER))
    }

    @Test
    fun `skipping is what both buttons do until they are told otherwise`() {
        assertThat(HeadsetActions.resolve(MediaIntent.SkipForward, skipping)?.action)
            .isEqualTo(HeadsetAction.SKIP)
        assertThat(HeadsetActions.resolve(MediaIntent.SkipBack, skipping)?.action)
            .isEqualTo(HeadsetAction.SKIP)
    }

    /**
     * Nothing is a choice, and it has to survive as far as the caller: a press resolved to
     * "do nothing" and a press that was never a side button must both end in silence.
     */
    @Test
    fun `nothing is carried through rather than quietly turning into a skip`() {
        val settings = HeadsetSettings(
            nextAction = HeadsetAction.NOTHING,
            previousAction = HeadsetAction.NOTHING,
        )

        assertThat(HeadsetActions.resolve(MediaIntent.SkipForward, settings)?.action)
            .isEqualTo(HeadsetAction.NOTHING)
    }

    @Test
    fun `presses that are not a side button resolve to nothing at all`() {
        listOf(
            MediaIntent.Play,
            MediaIntent.Pause,
            MediaIntent.PauseGlitch,
            MediaIntent.Duplicate,
        ).forEach { intent ->
            assertThat(HeadsetActions.resolve(intent, skipping)).isNull()
        }
    }

    /**
     * The two halves together. A headset that reports one press twice within the duplicate
     * window must move the book once, whatever the buttons are configured to do.
     */
    @Test
    fun `a repeated press from a noisy headset acts once`() {
        val classifier = MediaButtonClassifier()

        val first = classifier.classify(MediaButton.NEXT, atMs = 1_000, isPlaying = true)
        val echo = classifier.classify(MediaButton.NEXT, atMs = 1_020, isPlaying = true)

        assertThat(HeadsetActions.resolve(first, skipping)).isNotNull()
        assertThat(HeadsetActions.resolve(echo, skipping)).isNull()
    }
}
