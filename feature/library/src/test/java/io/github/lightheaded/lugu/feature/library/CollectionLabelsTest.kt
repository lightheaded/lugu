package io.github.lightheaded.lugu.feature.library

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.db.CollectionSummary
import org.junit.Test

/**
 * The words and the ordering the collections pages put on screen.
 *
 * Pinned because each is a claim somebody will act on: a count that reads as a failure to
 * load, an empty page that does not say where collections come from, or a list that
 * rearranges itself under a finger mid-tick.
 */
class CollectionLabelsTest {

    @Test
    fun `a count says how many books are in it`() {
        assertThat(collectionCountLine(12)).isEqualTo("12 books")
        assertThat(collectionCountLine(1)).isEqualTo("1 book")
    }

    @Test
    fun `an empty collection is named rather than counted`() {
        // "0 books" reads as a list that failed to load, which is the one thing it is not.
        assertThat(collectionCountLine(0)).isEqualTo("Empty")
    }

    @Test
    fun `a large count is grouped so it can be read at a glance`() {
        assertThat(collectionCountLine(1204)).isEqualTo("1,204 books")
    }

    @Test
    fun `an empty library says where collections come from`() {
        assertThat(emptyCollectionsLine(total = 0, query = "")).contains("made on the server")
    }

    @Test
    fun `a search that matched nothing is not the same emptiness`() {
        assertThat(emptyCollectionsLine(total = 6, query = "wint"))
            .isEqualTo("No collections match that.")
    }

    @Test
    fun `the search box stays away until the list is long enough to need it`() {
        assertThat(searchEarnsItsPlace(3)).isFalse()
        assertThat(searchEarnsItsPlace(40)).isTrue()
    }

    @Test
    fun `membership is listed by name, whatever is ticked`() {
        val collections = listOf(
            CollectionSummary(id = "c-2", name = "Winter reading", itemCount = 4),
            CollectionSummary(id = "c-1", name = "Lighthouse re-reads", itemCount = 2),
        )

        val choices = collectionChoices(collections, membership = setOf("c-2"))

        assertThat(choices.map { it.name })
            .containsExactly("Lighthouse re-reads", "Winter reading")
            .inOrder()
        assertThat(choices.single { it.contains }.id).isEqualTo("c-2")
    }

    @Test
    fun `a collection numbered ten comes after one numbered two`() {
        val collections = listOf(
            CollectionSummary(id = "c-10", name = "Riverton 10", itemCount = 1),
            CollectionSummary(id = "c-2", name = "Riverton 2", itemCount = 1),
        )

        assertThat(collectionChoices(collections, membership = emptySet()).map { it.name })
            .containsExactly("Riverton 2", "Riverton 10")
            .inOrder()
    }

    @Test
    fun `the confirmation names the collection, because nothing on the page changes`() {
        assertThat(collectionChangeLine("Winter reading", added = true))
            .isEqualTo("Added to Winter reading")
        assertThat(collectionChangeLine("Winter reading", added = false))
            .isEqualTo("Removed from Winter reading")
    }

    @Test
    fun `a collection with no name still gets a sentence`() {
        assertThat(collectionChangeLine(null, added = true)).isEqualTo("Added to the collection")
        assertThat(collectionChangeLine("  ", added = false))
            .isEqualTo("Removed from the collection")
    }
}
