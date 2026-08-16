package io.github.lightheaded.lugu.playback

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.db.EpisodeEntity
import io.github.lightheaded.lugu.core.sync.PodcastOrder
import org.junit.Test

/**
 * Which episode follows which.
 *
 * The whole of upstream app#473 and server#1321 is here: a serial is worked through
 * forwards and a news show backwards, and until now every listener got the serial's
 * answer. Both directions are tested against the same list, because the failure worth
 * catching is one direction quietly behaving like the other.
 */
class PodcastOrderTest {

    private val day = 24 * 60 * 60 * 1000L

    private fun episode(id: String, publishedDay: Long) = EpisodeEntity(
        serverId = "s",
        userId = "u",
        id = id,
        libraryItemId = "li_pod",
        title = "Episode $id",
        subtitle = null,
        description = null,
        episodeNumber = null,
        season = null,
        publishedAtMs = publishedDay * day,
        durationSec = 1_800.0,
        position = 0,
    )

    /** Newest first, as `EpisodeDao.latestUnfinished` returns them. */
    private val unfinished = listOf(episode("e5", 5), episode("e4", 4), episode("e2", 2), episode("e1", 1))

    @Test
    fun `oldest first moves forwards through the archive`() {
        val next = PodcastOrder.nextEpisode(unfinished, afterPublishedAtMs = 2 * day, oldestFirst = true)

        assertThat(next?.id).isEqualTo("e4")
    }

    @Test
    fun `newest first moves backwards through the archive`() {
        val next = PodcastOrder.nextEpisode(unfinished, afterPublishedAtMs = 4 * day, oldestFirst = false)

        assertThat(next?.id).isEqualTo("e2")
    }

    /** An episode already finished is not in the list, so it is stepped over rather than replayed. */
    @Test
    fun `a gap in the list is stepped over in both directions`() {
        assertThat(PodcastOrder.nextEpisode(unfinished, 1 * day, oldestFirst = true)?.id).isEqualTo("e2")
        assertThat(PodcastOrder.nextEpisode(unfinished, 5 * day, oldestFirst = false)?.id).isEqualTo("e4")
    }

    @Test
    fun `the end of the road in either direction is nothing`() {
        assertThat(PodcastOrder.nextEpisode(unfinished, 5 * day, oldestFirst = true)).isNull()
        assertThat(PodcastOrder.nextEpisode(unfinished, 1 * day, oldestFirst = false)).isNull()
        assertThat(PodcastOrder.nextEpisode(emptyList(), 3 * day, oldestFirst = true)).isNull()
    }

    /**
     * Feeds that publish a batch at one timestamp must not send the pair of them round in a
     * circle, each following the other for ever.
     */
    @Test
    fun `an episode published at the same instant is not offered`() {
        val sameDay = listOf(episode("a", 3), episode("b", 3))

        assertThat(PodcastOrder.nextEpisode(sameDay, 3 * day, oldestFirst = true)).isNull()
        assertThat(PodcastOrder.nextEpisode(sameDay, 3 * day, oldestFirst = false)).isNull()
    }

    /** The notice has to say which way it went, or a listener cannot tell a bug from a setting. */
    @Test
    fun `the notice names the direction`() {
        assertThat(PodcastOrder.reasonFor(oldestFirst = true)).isEqualTo("Next episode")
        assertThat(PodcastOrder.reasonFor(oldestFirst = false)).isEqualTo("Earlier episode")
    }
}
