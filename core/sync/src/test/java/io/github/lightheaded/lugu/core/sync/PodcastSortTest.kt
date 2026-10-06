package io.github.lightheaded.lugu.core.sync

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.ItemSort
import org.junit.Test

/**
 * A podcast library keeps its own sort, and an update keeps the one chosen before it.
 *
 * Until podcast libraries had a key of their own, one key held the sort for every library.
 * Somebody who chose "Latest episode" before the update must still see it after.
 */
class PodcastSortTest {

    @Test
    fun `the sort stored before the split carries over to the podcasts`() {
        assertThat(storedPodcastSort(own = null, shared = ItemSort.LATEST_EPISODE.id))
            .isEqualTo(ItemSort.LATEST_EPISODE)
    }

    @Test
    fun `once the podcasts have their own sort, a book choice does not change it`() {
        assertThat(storedPodcastSort(own = ItemSort.LATEST_EPISODE.id, shared = ItemSort.AUTHOR.id))
            .isEqualTo(ItemSort.LATEST_EPISODE)
    }

    @Test
    fun `nothing stored means title order`() {
        assertThat(storedPodcastSort(own = null, shared = null)).isEqualTo(ItemSort.TITLE)
    }
}
