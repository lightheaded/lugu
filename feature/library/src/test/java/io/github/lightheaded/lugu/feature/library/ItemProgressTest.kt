package io.github.lightheaded.lugu.feature.library

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.MediaProgress
import org.junit.Test

class ItemProgressTest {

    @Test
    fun `item level progress wins over any episode row`() {
        val rows = listOf(
            MediaProgress(libraryItemId = "book", currentTimeSec = 120.0, lastUpdateMs = 10),
            MediaProgress(libraryItemId = "book", episodeId = "e1", currentTimeSec = 30.0, lastUpdateMs = 99),
        )

        assertThat(ItemProgress.byItem(rows).getValue("book").currentTimeSec).isEqualTo(120.0)
    }

    @Test
    fun `a podcast falls back to its most recently updated episode`() {
        val rows = listOf(
            MediaProgress(libraryItemId = "pod", episodeId = "old", currentTimeSec = 10.0, lastUpdateMs = 100),
            MediaProgress(libraryItemId = "pod", episodeId = "new", currentTimeSec = 60.0, lastUpdateMs = 900),
            MediaProgress(libraryItemId = "pod", episodeId = "mid", currentTimeSec = 20.0, lastUpdateMs = 500),
        )

        val fallback = ItemProgress.byItem(rows).getValue("pod")

        // The episode id has to come through as well: it is what the resume affordance plays.
        assertThat(fallback.episodeId).isEqualTo("new")
        assertThat(fallback.currentTimeSec).isEqualTo(60.0)
    }

    @Test
    fun `items are kept apart`() {
        val rows = listOf(
            MediaProgress(libraryItemId = "a", episodeId = "e1", lastUpdateMs = 5),
            MediaProgress(libraryItemId = "b", episodeId = "e2", lastUpdateMs = 7),
        )

        assertThat(ItemProgress.byItem(rows).keys).containsExactly("a", "b")
    }

    @Test
    fun `an item with no progress at all is absent rather than zeroed`() {
        assertThat(ItemProgress.byItem(emptyList())).isEmpty()
    }
}
