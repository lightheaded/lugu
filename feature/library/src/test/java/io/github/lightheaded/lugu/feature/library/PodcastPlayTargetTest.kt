package io.github.lightheaded.lugu.feature.library

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.MediaProgress
import io.github.lightheaded.lugu.core.model.PodcastEpisode
import org.junit.Test

/**
 * Which episode a podcast's hero button plays.
 *
 * The rule reads top to bottom: an episode already in progress, then the newest episode
 * nobody has finished, then — with nothing left to offer — the newest episode again, so
 * the button is never left with nothing to do.
 */
class PodcastPlayTargetTest {

    @Test
    fun `an episode already in progress is continued by name`() {
        val rows = listOf(
            episode(id = "a", publishedAtMs = 2_000, progress = 0f, finished = false),
            episode(id = "b", publishedAtMs = 1_000, progress = 0.4f, finished = false),
        )
        val target = podcastPlayTarget(rows)
        assertThat(target?.episodeId).isEqualTo("b")
        assertThat(target?.label).isEqualTo("Continue: b")
    }

    @Test
    fun `two episodes in progress pick the one touched most recently`() {
        val rows = listOf(
            episode(id = "a", publishedAtMs = 1_000, progress = 0.2f, finished = false, lastUpdateMs = 500),
            episode(id = "b", publishedAtMs = 2_000, progress = 0.6f, finished = false, lastUpdateMs = 9_000),
        )
        assertThat(podcastPlayTarget(rows)?.episodeId).isEqualTo("b")
    }

    @Test
    fun `nothing in progress plays the newest unfinished episode, by publish date`() {
        val rows = listOf(
            episode(id = "old", publishedAtMs = 1_000, progress = 0f, finished = false),
            episode(id = "new", publishedAtMs = 5_000, progress = 0f, finished = false),
            episode(id = "newest-but-finished", publishedAtMs = 9_000, progress = 0f, finished = true),
        )
        val target = podcastPlayTarget(rows)
        assertThat(target?.episodeId).isEqualTo("new")
        assertThat(target?.label).isEqualTo("Play latest episode")
    }

    @Test
    fun `every episode finished still offers the newest one, said as a replay`() {
        val rows = listOf(
            episode(id = "old", publishedAtMs = 1_000, progress = 1f, finished = true),
            episode(id = "new", publishedAtMs = 5_000, progress = 1f, finished = true),
        )
        val target = podcastPlayTarget(rows)
        assertThat(target?.episodeId).isEqualTo("new")
        assertThat(target?.label).isEqualTo("Play latest episode again")
    }

    @Test
    fun `no episodes at all leaves nothing to play`() {
        assertThat(podcastPlayTarget(emptyList())).isNull()
    }

    private fun episode(
        id: String,
        publishedAtMs: Long,
        progress: Float,
        finished: Boolean,
        lastUpdateMs: Long = 0L,
    ) = EpisodeRow(
        episode = PodcastEpisode(
            id = id,
            libraryItemId = "pod",
            title = id,
            publishedAtMs = publishedAtMs,
        ),
        progress = MediaProgress(
            libraryItemId = "pod",
            episodeId = id,
            progress = progress.toDouble(),
            isFinished = finished,
            lastUpdateMs = lastUpdateMs,
        ),
    )
}
