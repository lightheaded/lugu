package io.github.lightheaded.lugu.playback

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackEventsTest {

    @Test
    fun `a play is a play whatever asked for it`() {
        assertThat(PlaybackEvents.forPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE))
            .isEqualTo(PlaybackEvents.PLAY)
    }

    @Test
    fun `a pause keeps the cause the player reports`() {
        assertThat(PlaybackEvents.forPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST))
            .isEqualTo(PlaybackEvents.PAUSE)
        assertThat(PlaybackEvents.forPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY))
            .isEqualTo(PlaybackEvents.PAUSE_NOISY)
        assertThat(PlaybackEvents.forPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS))
            .isEqualTo(PlaybackEvents.PAUSE_FOCUS)
    }

    @Test
    fun `a seek reason is not a playback event`() {
        assertThat(PlaybackEvents.isPlaybackEvent(SeekReasons.SKIP_BACK)).isFalse()
        assertThat(PlaybackEvents.isPlaybackEvent(PlaybackEvents.PAUSE_SLEEP_TIMER)).isTrue()
    }
}
