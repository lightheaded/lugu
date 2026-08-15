package io.github.lightheaded.lugu.feature.library

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.MediaProgress
import io.github.lightheaded.lugu.core.model.PodcastEpisode
import org.junit.Test

/**
 * Which way the selection bar's single marking button points.
 *
 * It has to be decided from the rows rather than from a toggle the bar remembers, because
 * the bar outlives no selection: press it twice on the same eight episodes and the second
 * press must be the undo of the first.
 */
class MarkFinishedTest {

    @Test
    fun `a mixed selection is finished, not un-finished`() {
        val rows = listOf(row("a", finished = true), row("b", finished = false))
        assertThat(markFinishedTarget(rows)).isTrue()
    }

    @Test
    fun `a selection that is already finished offers the undo`() {
        val rows = listOf(row("a", finished = true), row("b", finished = true))
        assertThat(markFinishedTarget(rows)).isFalse()
    }

    @Test
    fun `an untouched selection is finished`() {
        assertThat(markFinishedTarget(listOf(row("a", finished = false)))).isTrue()
    }

    @Test
    fun `an episode with no progress row at all counts as unfinished`() {
        val rows = listOf(EpisodeRow(episode = episode("a"), progress = null))
        assertThat(markFinishedTarget(rows)).isTrue()
    }

    @Test
    fun `nothing picked reads as finishing, so the disabled button does not mislead`() {
        assertThat(markFinishedTarget(emptyList())).isTrue()
    }

    private fun row(id: String, finished: Boolean) = EpisodeRow(
        episode = episode(id),
        progress = MediaProgress(
            libraryItemId = "pod",
            episodeId = id,
            isFinished = finished,
        ),
    )

    private fun episode(id: String) = PodcastEpisode(id = id, libraryItemId = "pod", title = id)
}
