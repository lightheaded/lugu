package io.github.lightheaded.lugu.feature.library

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.db.CollectionSummary
import io.github.lightheaded.lugu.core.model.LibraryItem
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.sync.EpisodeHit
import io.github.lightheaded.lugu.core.sync.SearchResults
import org.junit.Test

class SearchLabelsTest {

    private val book = LibraryItem(
        id = "b1",
        libraryId = "lib",
        mediaType = MediaType.BOOK,
        title = "The Lighthouse Wakes",
        authorName = "James T. R. Corven",
        narratorName = "Jefferson Vale",
        seriesName = "The Breakwater #1",
    )
    private val podcast = LibraryItem(
        id = "p1",
        libraryId = "lib",
        mediaType = MediaType.PODCAST,
        title = "Night Shift Radio",
        authorName = "Ada Merriweather",
    )

    @Test
    fun `an empty box says what the search covers`() {
        assertThat(searchHint(SearchUiState())).contains("episodes, authors, narrators")
    }

    @Test
    fun `nothing matches is said only once the answer is for the words in the box`() {
        val waiting = SearchUiState(query = "salt", results = SearchResults(query = "sal"))
        val answered = SearchUiState(query = "salt ", results = SearchResults(query = "salt"))

        assertThat(searchHint(waiting)).isNull()
        assertThat(searchHint(answered)).isEqualTo("Nothing matches “salt”.")
    }

    @Test
    fun `a collection alone is a result`() {
        val state = SearchUiState(
            query = "salt",
            results = SearchResults(query = "salt"),
            collections = listOf(CollectionSummary("c1", "Salt reads", 3)),
        )

        assertThat(searchHint(state)).isNull()
    }

    @Test
    fun `the item section is named after what it holds`() {
        assertThat(itemSectionLabel(listOf(book))).isEqualTo("Books")
        assertThat(itemSectionLabel(listOf(podcast))).isEqualTo("Podcasts")
        assertThat(itemSectionLabel(listOf(book, podcast))).isEqualTo("Books and podcasts")
    }

    @Test
    fun `the narrator is named only when the narrator is what matched`() {
        assertThat(itemSubline(book, "vale")).isEqualTo("Read by Jefferson Vale · James T. R. Corven · The Breakwater #1")
        assertThat(itemSubline(book, "lighthouse")).isEqualTo("James T. R. Corven · The Breakwater #1")
        assertThat(itemSubline(podcast, "night")).isEqualTo("Podcast · Ada Merriweather")
    }

    @Test
    fun `an episode is named with its podcast and its date`() {
        val hit = EpisodeHit("p1", "e1", "Harbour lights", "Night Shift Radio", publishedAtMs = 0L, durationSec = 60.0)

        assertThat(episodeHitSubline(hit)).isEqualTo("Night Shift Radio")
    }
}
