package io.github.lightheaded.lugu.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which position writes start a push of their own.
 *
 * A write that starts no push waits in the outbox for the next pause. That is right only
 * while the player plays, because only then is a pause certain to follow. The case that
 * broke it is a cued volume: written at the moment it loads, never played, never paused.
 */
class ProgressPushRuleTest {

    @Test
    fun `a pause pushes`() {
        assertThat(ProgressPushRule.pushesNow("pause", isPlaying = false)).isTrue()
    }

    @Test
    fun `a volume cued and not started pushes, because no pause will follow`() {
        assertThat(ProgressPushRule.pushesNow("transition", isPlaying = false)).isTrue()
    }

    @Test
    fun `the end of a book pushes`() {
        assertThat(ProgressPushRule.pushesNow("ended", isPlaying = false)).isTrue()
    }

    @Test
    fun `a seek while paused pushes`() {
        assertThat(ProgressPushRule.pushesNow("seek", isPlaying = false)).isTrue()
    }

    @Test
    fun `a teardown of a paused service pushes`() {
        assertThat(ProgressPushRule.pushesNow("destroy", isPlaying = false)).isTrue()
        assertThat(ProgressPushRule.pushesNow("task-removed", isPlaying = false)).isTrue()
    }

    @Test
    fun `a write while playing waits for the pause that follows it`() {
        for (reason in listOf("seek", "transition", "headset-next-item", "task-removed")) {
            assertThat(ProgressPushRule.pushesNow(reason, isPlaying = true)).isFalse()
        }
    }

    @Test
    fun `the tick never pushes`() {
        assertThat(ProgressPushRule.pushesNow(ProgressPushRule.TICK, isPlaying = true)).isFalse()
        assertThat(ProgressPushRule.pushesNow(ProgressPushRule.TICK, isPlaying = false)).isFalse()
    }
}
