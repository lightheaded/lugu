package io.github.lightheaded.lugu.core.sync

import io.github.lightheaded.lugu.core.db.BrowseGroup
import io.github.lightheaded.lugu.core.db.EpisodeSearchRow
import io.github.lightheaded.lugu.core.db.ItemSearchRow
import io.github.lightheaded.lugu.core.model.LibraryItem
import io.github.lightheaded.lugu.core.model.MediaType

/** One episode that a search found, with the podcast it belongs to. */
data class EpisodeHit(
    val itemId: String,
    val episodeId: String,
    val title: String,
    val podcastTitle: String,
    val publishedAtMs: Long,
    val durationSec: Double,
)

/**
 * What one search on Home found, in every kind of thing a library holds.
 *
 * [query] is the text these results answer. The screen compares it with the text in the
 * box, so it can tell "nothing matches" from "the answer has not arrived yet".
 */
data class SearchResults(
    val query: String = "",
    val items: List<LibraryItem> = emptyList(),
    val episodes: List<EpisodeHit> = emptyList(),
    val authors: List<BrowseGroup> = emptyList(),
    val narrators: List<BrowseGroup> = emptyList(),
    val series: List<BrowseGroup> = emptyList(),
) {
    val isEmpty: Boolean
        get() = items.isEmpty() && episodes.isEmpty() && authors.isEmpty() &&
            narrators.isEmpty() && series.isEmpty()
}

/**
 * The matching for Home's search, apart from Room so that it can be tested by itself.
 *
 * Every name is matched as part of a word and without case, in Kotlin, because SQLite
 * folds case for ASCII letters only. The full-text index adds the items whose
 * description, genres or tags hold the words, which no name list can find.
 */
object SearchEverything {

    /** Whether [text] holds [needle], ignoring case, where [needle] is already lowercase. */
    fun contains(text: String?, needle: String): Boolean =
        text != null && text.lowercase().contains(needle)

    fun needleOf(query: String): String = query.trim().lowercase()

    fun assemble(
        query: String,
        named: List<ItemSearchRow>,
        indexed: List<LibraryItem>,
        episodes: List<EpisodeSearchRow>,
        authors: List<BrowseGroup>,
        narrators: List<BrowseGroup>,
        series: List<BrowseGroup>,
    ): SearchResults {
        val needle = needleOf(query)
        if (needle.isEmpty()) return SearchResults(query = query.trim())

        val byName = named
            .filter { row ->
                contains(row.title, needle) || contains(row.authorName, needle) ||
                    contains(row.narratorName, needle) || contains(row.seriesName, needle)
            }
            .map { it.toItem() }
        val items = (byName + indexed)
            .distinctBy { it.id }
            .sortedWith(compareBy<LibraryItem> { titleRank(it.title, needle) }.thenBy { it.title.lowercase() })

        return SearchResults(
            query = query.trim(),
            items = items,
            episodes = episodes
                .filter { contains(it.title, needle) || contains(it.subtitle, needle) }
                .map {
                    EpisodeHit(
                        itemId = it.libraryItemId,
                        episodeId = it.id,
                        title = it.title,
                        podcastTitle = it.podcastTitle,
                        publishedAtMs = it.publishedAtMs,
                        durationSec = it.durationSec,
                    )
                },
            authors = groupsMatching(authors, needle),
            narrators = groupsMatching(narrators, needle),
            series = groupsMatching(series, needle),
        )
    }

    /**
     * A title that starts with the words comes first, then a title that holds them, then an
     * item found by its author, narrator, series or description.
     */
    internal fun titleRank(title: String, needle: String): Int {
        val lower = title.lowercase()
        return when {
            lower.startsWith(needle) -> 0
            lower.contains(needle) -> 1
            else -> 2
        }
    }

    private fun groupsMatching(groups: List<BrowseGroup>, needle: String): List<BrowseGroup> =
        groups
            .filter { contains(it.name, needle) }
            .sortedWith(compareBy<BrowseGroup> { titleRank(it.name, needle) }.thenBy { it.name.lowercase() })

    private fun ItemSearchRow.toItem(): LibraryItem = LibraryItem(
        id = id,
        libraryId = libraryId,
        mediaType = MediaType.fromWire(mediaType),
        title = title,
        authorName = authorName,
        narratorName = narratorName,
        seriesName = seriesName,
    )
}
