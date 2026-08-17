package io.github.lightheaded.lugu.feature.library

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.LibraryItem
import io.github.lightheaded.lugu.core.model.MediaProgress
import io.github.lightheaded.lugu.core.model.MediaType
import org.junit.Test

/**
 * What a grid tile is allowed to conclude from the progress it was handed.
 *
 * A podcast has no progress row at the item level, so its tile borrows the most recently
 * played episode's. Drawing that is right — it is the position of the thing the tile would
 * resume — and treating it as a fact about the feed is not.
 */
class LibraryRowTest {

    private fun book(progress: MediaProgress?) = LibraryRow(
        LibraryItem(id = "book", libraryId = "l", mediaType = MediaType.BOOK, title = "The Breakwater"),
        progress,
    )

    private fun podcast(progress: MediaProgress?) = LibraryRow(
        LibraryItem(id = "pod", libraryId = "l", mediaType = MediaType.PODCAST, title = "Riverton"),
        progress,
    )

    @Test
    fun `a podcast tile draws its latest episode's position`() {
        val row = podcast(MediaProgress(libraryItemId = "pod", episodeId = "e9", progress = 0.62))

        // The bug: this was zero for every podcast, however much had been listened to,
        // because the grid looked progress up by item id alone and a podcast has no such row.
        assertThat(row.progressFraction).isWithin(0.001f).of(0.62f)
    }

    @Test
    fun `one finished episode does not finish the feed`() {
        val row = podcast(
            MediaProgress(libraryItemId = "pod", episodeId = "e9", progress = 1.0, isFinished = true),
        )

        assertThat(row.isFinished).isFalse()
        // And it still draws, because the tile is about where the listening got to.
        assertThat(row.progressFraction).isEqualTo(1f)
    }

    @Test
    fun `a finished book is finished`() {
        val row = book(MediaProgress(libraryItemId = "book", progress = 1.0, isFinished = true))

        assertThat(row.isFinished).isTrue()
    }

    @Test
    fun `an item with no progress claims none`() {
        assertThat(book(null).progressFraction).isEqualTo(0f)
        assertThat(book(null).isFinished).isFalse()
        assertThat(book(null).progressIsEpisode).isFalse()
    }

    @Test
    fun `a fraction beyond one is clamped rather than drawn past the end`() {
        val row = book(MediaProgress(libraryItemId = "book", progress = 1.4))

        assertThat(row.progressFraction).isEqualTo(1f)
    }

    @Test
    fun `the spoken description says which thing the percentage is about`() {
        assertThat(book(MediaProgress(libraryItemId = "book", progress = 0.4)).progressDescription)
            .isEqualTo("40% listened")
        assertThat(podcast(MediaProgress(libraryItemId = "pod", episodeId = "e", progress = 0.4)).progressDescription)
            .isEqualTo("Latest episode 40% listened")
    }
}
