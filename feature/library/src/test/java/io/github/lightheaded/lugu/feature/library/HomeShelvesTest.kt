package io.github.lightheaded.lugu.feature.library

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.db.DownloadState
import io.github.lightheaded.lugu.core.download.DownloadStatus
import io.github.lightheaded.lugu.core.model.LibraryItem
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.sync.Shelf
import io.github.lightheaded.lugu.core.sync.ShelfEntry
import io.github.lightheaded.lugu.core.sync.ShelfKind
import org.junit.Test

/**
 * The resume card and the shelves under it.
 *
 * The card and the first shelf both say "Continue listening", so the card's own item must
 * not be repeated on the shelf below it.
 */
class HomeShelvesTest {

    private fun book(id: String, title: String) = LibraryItem(
        id = id,
        libraryId = "lib",
        mediaType = MediaType.BOOK,
        title = title,
        authorName = "James T. R. Corven",
        durationSec = 3_600.0,
    )

    private val lighthouse = book("b1", "The Lighthouse Wakes")
    private val tidelands = book("b2", "The Tidelands")
    private val breakwater = book("b3", "The Breakwater")

    @Test
    fun `the card's item is not repeated on the continue shelf`() {
        val shelves = listOf(
            Shelf(ShelfKind.CONTINUE, listOf(ShelfEntry(lighthouse), ShelfEntry(tidelands))),
        )

        val (card, rows) = homeShelves(shelves, emptyMap(), emptySet())

        assertThat(card?.itemId).isEqualTo("b1")
        assertThat(rows.single().cards.map { it.itemId }).containsExactly("b2")
    }

    @Test
    fun `a continue shelf with only the card's item is not shown`() {
        val shelves = listOf(
            Shelf(ShelfKind.CONTINUE, listOf(ShelfEntry(lighthouse))),
            Shelf(ShelfKind.NEXT_IN_SERIES, listOf(ShelfEntry(breakwater))),
        )

        val (card, rows) = homeShelves(shelves, emptyMap(), emptySet())

        assertThat(card?.itemId).isEqualTo("b1")
        assertThat(rows.map { it.kind }).containsExactly(ShelfKind.NEXT_IN_SERIES)
    }

    @Test
    fun `other shelves keep every entry, the card's item too`() {
        val shelves = listOf(
            Shelf(ShelfKind.CONTINUE, listOf(ShelfEntry(lighthouse), ShelfEntry(tidelands))),
            Shelf(ShelfKind.ALMOST_FINISHED, listOf(ShelfEntry(lighthouse))),
        )

        val (_, rows) = homeShelves(shelves, emptyMap(), emptySet())

        assertThat(rows.last().cards.map { it.itemId }).containsExactly("b1")
    }

    @Test
    fun `with the continue shelf hidden there is no card`() {
        val shelves = listOf(Shelf(ShelfKind.NEXT_IN_SERIES, listOf(ShelfEntry(breakwater))))

        val (card, rows) = homeShelves(shelves, emptyMap(), emptySet())

        assertThat(card).isNull()
        assertThat(rows.single().cards.map { it.itemId }).containsExactly("b3")
    }

    @Test
    fun `see all is offered only past the cap, counted without the card`() {
        val entries = (0..10).map { ShelfEntry(book("c$it", "Volume $it")) }

        val (_, rows) = homeShelves(listOf(Shelf(ShelfKind.CONTINUE, entries)), emptyMap(), emptySet())

        // Eleven entries, one of them on the card: ten are left, and ten fit.
        assertThat(rows.single().cards).hasSize(10)
        assertThat(rows.single().hasMore).isFalse()
    }

    @Test
    fun `a card knows its item is downloaded`() {
        val shelves = listOf(
            Shelf(ShelfKind.CONTINUE, listOf(ShelfEntry(lighthouse), ShelfEntry(tidelands))),
        )

        val (card, rows) = homeShelves(shelves, emptyMap(), setOf("b2"))

        assertThat(card?.isDownloaded).isFalse()
        assertThat(rows.single().cards.single().isDownloaded).isTrue()
    }

    @Test
    fun `only a finished download counts as downloaded`() {
        val done = DownloadStatus("b1", null, "", null, DownloadState.COMPLETED, 1f, 0, 0, null)
        val running = DownloadStatus("b2", null, "", null, DownloadState.DOWNLOADING, 0.5f, 0, 0, null)

        assertThat(ItemProgress.downloadedItemIds(listOf(done, running))).containsExactly("b1")
    }
}
