package io.github.lightheaded.lugu.feature.library

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.ListFilter
import org.junit.Test

/** An empty episode list names what hides the rows, so the way back is obvious. */
class EpisodeEmptyLineTest {

    @Test
    fun `a search is named`() {
        assertThat(noMatchingEpisodesLine("lighthouse ", ListFilter.FINISHED))
            .isEqualTo("No episodes match “lighthouse”.")
    }

    @Test
    fun `a filter is named when nothing was typed`() {
        assertThat(noMatchingEpisodesLine("", ListFilter.DOWNLOADED))
            .isEqualTo("No episodes match “Downloaded”.")
    }
}
