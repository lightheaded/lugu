package io.github.lightheaded.lugu.feature.player

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.playback.NowPlaying
import org.junit.Test

/**
 * The transcoding note stays put away once the listener dismisses it (UX audit, "The
 * transcoding note resurrects").
 *
 * The dismissal used to be a `remember` in [PlayerScreen], so it ended with each visit to
 * the player. A new [PlayerViewModel] ends the same way, which is why the dismissals live in
 * one [TranscodingNoticeDismissals] that both visits share.
 */
class TranscodingNoticeTest {

    private val dismissals = TranscodingNoticeDismissals()

    @Test
    fun `a dismissed note stays dismissed for the same item on the next visit`() {
        val book = nowPlaying("li_book", episodeId = null)
        assertThat(transcodingNoticeGone(book, dismissals.keys.value)).isFalse()

        dismissals.dismiss("li_book", null)

        // A second visit reads the same holder, as a new view model would.
        assertThat(transcodingNoticeGone(book, dismissals.keys.value)).isTrue()
    }

    @Test
    fun `the note of another item is still shown`() {
        dismissals.dismiss("li_book", null)

        assertThat(transcodingNoticeGone(nowPlaying("li_other", null), dismissals.keys.value)).isFalse()
    }

    @Test
    fun `each episode of a show is its own stream, so its own note`() {
        dismissals.dismiss("li_show", "ep_1")

        assertThat(transcodingNoticeGone(nowPlaying("li_show", "ep_1"), dismissals.keys.value)).isTrue()
        assertThat(transcodingNoticeGone(nowPlaying("li_show", "ep_2"), dismissals.keys.value)).isFalse()
    }

    @Test
    fun `with nothing loaded there is no note to have put away`() {
        dismissals.dismiss("li_book", null)

        assertThat(transcodingNoticeGone(null, dismissals.keys.value)).isFalse()
    }

    private fun nowPlaying(itemId: String, episodeId: String?) = NowPlaying(
        libraryItemId = itemId,
        episodeId = episodeId,
        title = "A made-up title",
        author = null,
        coverUrl = null,
        durationSec = 600.0,
        tracks = emptyList(),
        chapters = emptyList(),
        ledgerId = "ledger",
        isTranscoded = true,
    )
}
