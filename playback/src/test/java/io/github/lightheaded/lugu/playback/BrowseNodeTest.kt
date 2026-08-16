package io.github.lightheaded.lugu.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Node ids are the only thing a car gives back.
 *
 * There is no session state to consult on the other side of one of these strings — the
 * process may have died between the browse and the tap — so an id that does not survive
 * a round trip is a book that will not play, with nothing to debug from.
 */
class BrowseNodeTest {

    @Test
    fun `every node survives a round trip`() {
        val nodes = listOf(
            BrowseNode.Root,
            BrowseNode.Continue,
            BrowseNode.UpNext,
            BrowseNode.Downloaded,
            BrowseNode.AllSeries,
            BrowseNode.AllPodcasts,
            BrowseNode.LatestEpisodes,
            BrowseNode.Libraries,
            BrowseNode.Series("The Breakwater"),
            BrowseNode.Podcast("li_pod"),
            BrowseNode.Library("lib_1"),
            BrowseNode.Playable("li_1", null),
            BrowseNode.Playable("li_pod", "ep_7"),
        )

        nodes.forEach { assertThat(BrowseNode.parse(it.id)).isEqualTo(it) }
    }

    /**
     * The reason nothing is escaped: everything after a prefix is taken verbatim, so a
     * series whose name contains the separator is still one node.
     */
    @Test
    fun `a series with slashes in its name is still one node`() {
        val node = BrowseNode.Series("Wool / Shift / Dust")

        assertThat(BrowseNode.parse(node.id)).isEqualTo(node)
    }

    @Test
    fun `a book and an episode of it are different nodes`() {
        val book = BrowseNode.Playable("li_1", null)
        val episode = BrowseNode.Playable("li_1", "ep_1")

        assertThat(book.id).isNotEqualTo(episode.id)
        assertThat(BrowseNode.parse(book.id)).isEqualTo(book)
        assertThat(BrowseNode.parse(episode.id)).isEqualTo(episode)
    }

    /** The series node and the list-of-series node must not collide. */
    @Test
    fun `the series list is not a series`() {
        assertThat(BrowseNode.parse(BrowseNode.AllSeries.id)).isEqualTo(BrowseNode.AllSeries)
        assertThat(BrowseNode.parse(BrowseNode.Series("x").id)).isEqualTo(BrowseNode.Series("x"))
    }

    @Test
    fun `ids from somewhere else are unknown rather than wrong`() {
        listOf(
            "",
            "android.resource://whatever",
            "lugu/",
            "lugu/series/",
            "lugu/play/",
            "lugu/play/li_1",
            "lugu/play/|ep_1",
        ).forEach { assertThat(BrowseNode.parse(it)).isEqualTo(BrowseNode.Unknown) }
    }
}
