package io.github.lightheaded.lugu.feature.library

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.LibraryItem
import io.github.lightheaded.lugu.core.model.MediaProgress
import io.github.lightheaded.lugu.core.model.MediaType
import org.junit.Test

/**
 * The grid's "Add to collection": which selected rows become the request, what the line
 * says afterwards, and which way the bar's one marking button goes.
 *
 * The view model is a final class over final Room and Ktor classes, so it cannot be built
 * here. Its two decisions are these pure functions, and [LibraryViewModel] only wires them
 * to the repository.
 */
class CollectionBatchTest {

    private fun row(id: String, type: MediaType = MediaType.BOOK, finished: Boolean = false) = LibraryRow(
        LibraryItem(id = id, libraryId = "lib_1", mediaType = type, title = "Title $id"),
        if (finished) MediaProgress(libraryItemId = id, progress = 1.0, isFinished = true) else null,
    )

    // region the request

    @Test
    fun `every selected book goes into the request, in the grid's order`() {
        val plan = collectionAddPlan(listOf(row("b2"), row("b1"), row("b3")))

        assertThat(plan.bookIds).containsExactly("b2", "b1", "b3").inOrder()
        assertThat(plan.skippedPodcasts).isEqualTo(0)
    }

    @Test
    fun `a podcast stays out of the request and is counted`() {
        val plan = collectionAddPlan(listOf(row("b1"), row("p1", MediaType.PODCAST), row("b2")))

        assertThat(plan.bookIds).containsExactly("b1", "b2").inOrder()
        assertThat(plan.skippedPodcasts).isEqualTo(1)
    }

    @Test
    fun `a selection of podcasts only makes an empty request`() {
        val plan = collectionAddPlan(listOf(row("p1", MediaType.PODCAST), row("p2", MediaType.PODCAST)))

        assertThat(plan.bookIds).isEmpty()
        assertThat(plan.skippedPodcasts).isEqualTo(2)
    }

    // endregion

    // region the line

    @Test
    fun `a full add names the collection and the count`() {
        val plan = CollectionAddPlan(bookIds = listOf("b1", "b2", "b3"), skippedPodcasts = 0)

        assertThat(collectionBatchLine("Winter reading", plan, held = 3))
            .isEqualTo("Added 3 books to Winter reading")
    }

    @Test
    fun `one book is one book`() {
        val plan = CollectionAddPlan(bookIds = listOf("b1"), skippedPodcasts = 0)

        assertThat(collectionBatchLine("Winter reading", plan, held = 1))
            .isEqualTo("Added 1 book to Winter reading")
    }

    @Test
    fun `books the server dropped are not claimed`() {
        val plan = CollectionAddPlan(bookIds = listOf("b1", "b2", "b3"), skippedPodcasts = 0)

        assertThat(collectionBatchLine("Winter reading", plan, held = 2))
            .isEqualTo("Added 2 of 3 books to Winter reading")
    }

    @Test
    fun `skipped podcasts are named`() {
        val plan = CollectionAddPlan(bookIds = listOf("b1", "b2"), skippedPodcasts = 1)

        assertThat(collectionBatchLine("Winter reading", plan, held = 2))
            .isEqualTo("Added 2 books to Winter reading. Collections hold books only, so 1 podcast stayed out")
    }

    @Test
    fun `a collection with no name is described rather than left blank`() {
        val plan = CollectionAddPlan(bookIds = listOf("b1", "b2"), skippedPodcasts = 0)

        assertThat(collectionBatchLine(null, plan, held = 2)).isEqualTo("Added 2 books to the collection")
        assertThat(collectionBatchLine(" ", plan, held = 2)).isEqualTo("Added 2 books to the collection")
    }

    // endregion

    // region the marking button

    @Test
    fun `a selection with one unfinished book offers finishing`() {
        assertThat(markBooksFinishedTarget(listOf(row("b1", finished = true), row("b2")))).isTrue()
    }

    @Test
    fun `a selection of finished books offers un-finishing`() {
        assertThat(markBooksFinishedTarget(listOf(row("b1", finished = true), row("b2", finished = true))))
            .isFalse()
    }

    @Test
    fun `an empty selection reads as finishing`() {
        assertThat(markBooksFinishedTarget(emptyList())).isTrue()
    }

    // endregion
}
