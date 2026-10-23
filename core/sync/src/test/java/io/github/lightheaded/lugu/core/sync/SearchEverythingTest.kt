package io.github.lightheaded.lugu.core.sync

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.db.BrowseGroup
import io.github.lightheaded.lugu.core.db.EpisodeSearchRow
import io.github.lightheaded.lugu.core.db.ItemSearchRow
import io.github.lightheaded.lugu.core.model.LibraryItem
import io.github.lightheaded.lugu.core.model.MediaType
import org.junit.Test

class SearchEverythingTest {

    private fun named(
        id: String,
        title: String,
        author: String? = null,
        narrator: String? = null,
        series: String? = null,
        mediaType: String = "BOOK",
    ) = ItemSearchRow(id, "lib", mediaType, title, author, narrator, series)

    private fun episode(id: String, title: String, subtitle: String? = null) =
        EpisodeSearchRow(id, "p1", title, subtitle, publishedAtMs = 0, durationSec = 60.0, podcastTitle = "Night Shift Radio")

    private fun search(
        query: String,
        named: List<ItemSearchRow> = emptyList(),
        indexed: List<LibraryItem> = emptyList(),
        episodes: List<EpisodeSearchRow> = emptyList(),
        authors: List<BrowseGroup> = emptyList(),
        narrators: List<BrowseGroup> = emptyList(),
        series: List<BrowseGroup> = emptyList(),
    ) = SearchEverything.assemble(query, named, indexed, episodes, authors, narrators, series)

    @Test
    fun `part of a word in the middle of a title is found`() {
        val found = search("house", named = listOf(named("b1", "The Lighthouse Wakes"), named("b2", "Salt")))

        assertThat(found.items.map { it.id }).containsExactly("b1")
    }

    @Test
    fun `case is ignored for letters outside ASCII too`() {
        val found = search("õnne", named = listOf(named("b1", "Õnne 13")), authors = listOf(BrowseGroup("ÜLO ÕUN", 2)))
        val author = search("ülo õun", authors = listOf(BrowseGroup("Ülo Õun", 2)))

        assertThat(found.items.map { it.id }).containsExactly("b1")
        assertThat(author.authors.map { it.name }).containsExactly("Ülo Õun")
    }

    @Test
    fun `an item is found by its author, narrator or series`() {
        val rows = listOf(
            named("b1", "One", author = "Ada Merriweather"),
            named("b2", "Two", narrator = "Ada Vale"),
            named("b3", "Three", series = "Adamant #1"),
            named("b4", "Four", author = "Peregrine Osk"),
        )

        assertThat(search("ada", named = rows).items.map { it.id }).containsExactly("b1", "b2", "b3")
    }

    @Test
    fun `an item the index found by its description is kept, and once only`() {
        val indexed = listOf(
            LibraryItem(id = "b1", libraryId = "lib", mediaType = MediaType.BOOK, title = "Salt"),
            LibraryItem(id = "b2", libraryId = "lib", mediaType = MediaType.BOOK, title = "Keeper of the Lamp"),
        )

        val found = search("keeper", named = listOf(named("b2", "Keeper of the Lamp")), indexed = indexed)

        assertThat(found.items.map { it.id }).containsExactly("b2", "b1").inOrder()
    }

    @Test
    fun `a title that starts with the words comes before one that holds them`() {
        val rows = listOf(
            named("b1", "Beyond the Salt"),
            named("b2", "Ashes", author = "Saltmarsh"),
            named("b3", "Salt and Shortwave"),
        )

        assertThat(search("salt", named = rows).items.map { it.id }).containsExactly("b3", "b1", "b2").inOrder()
    }

    @Test
    fun `episodes match on the title and the subtitle and keep their order`() {
        val rows = listOf(
            episode("e1", "Harbour lights", subtitle = null),
            episode("e2", "Episode 12", subtitle = "Back to the harbour"),
            episode("e3", "Mountains"),
        )

        val found = search("harbour", episodes = rows)

        assertThat(found.episodes.map { it.episodeId }).containsExactly("e1", "e2").inOrder()
        assertThat(found.episodes.first().podcastTitle).isEqualTo("Night Shift Radio")
    }

    @Test
    fun `groups match by name, best first`() {
        val narrators = listOf(BrowseGroup("Jefferson Vale", 3), BrowseGroup("Valentina Ross", 1), BrowseGroup("Osk", 1))

        assertThat(search("val", narrators = narrators).narrators.map { it.name })
            .containsExactly("Valentina Ross", "Jefferson Vale").inOrder()
    }

    @Test
    fun `blank words find nothing and say which words they answer`() {
        val found = search("   ", named = listOf(named("b1", "Salt")), authors = listOf(BrowseGroup("Salt", 1)))

        assertThat(found.isEmpty).isTrue()
        assertThat(found.query).isEmpty()
        assertThat(search(" salt ", named = listOf(named("b1", "Salt"))).query).isEqualTo("salt")
    }
}
