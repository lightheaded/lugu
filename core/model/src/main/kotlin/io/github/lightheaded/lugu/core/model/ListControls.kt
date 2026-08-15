package io.github.lightheaded.lugu.core.model

/**
 * How a list is ordered and narrowed.
 *
 * Declared in the model rather than in a screen because the same two questions — what
 * order, and which subset — are asked of the library grid, the episode list and the
 * downloads screen. Building the answer three times is how three screens end up sorting
 * by three different notions of "recent".
 */

/** Ordering for a list of library items. */
enum class ItemSort(val id: String, val label: String) {
    TITLE("title", "Title"),
    AUTHOR("author", "Author"),
    ADDED("added", "Recently added"),
    DURATION("duration", "Length"),
    PROGRESS("progress", "Progress"),

    /** Only meaningful where rows have bytes on the phone — the downloads screen. */
    SIZE("size", "Largest first"),
    ;

    companion object {
        fun fromId(id: String?): ItemSort = entries.firstOrNull { it.id == id } ?: TITLE
    }
}

/**
 * Ordering for a list of podcast episodes.
 *
 * Newest first is the default because a podcast is a feed: the episode most people want
 * is the one that arrived most recently, and a feed sorted by title is a feed nobody can
 * navigate.
 */
enum class EpisodeSort(val id: String, val label: String) {
    NEWEST("newest", "Newest first"),
    OLDEST("oldest", "Oldest first"),
    LONGEST("longest", "Longest"),
    SHORTEST("shortest", "Shortest"),
    TITLE("title", "Title"),
    ;

    companion object {
        fun fromId(id: String?): EpisodeSort = entries.firstOrNull { it.id == id } ?: NEWEST
    }
}

/**
 * Which rows survive.
 *
 * Deliberately a small closed set rather than a query language. These are the five
 * questions actually asked of a list of things to listen to; anything else is what the
 * search box is for.
 */
enum class ListFilter(val id: String, val label: String) {
    ALL("all", "All"),
    UNPLAYED("unplayed", "Not started"),
    IN_PROGRESS("in_progress", "In progress"),
    FINISHED("finished", "Finished"),
    DOWNLOADED("downloaded", "Downloaded"),
    ;

    companion object {
        fun fromId(id: String?): ListFilter = entries.firstOrNull { it.id == id } ?: ALL
    }
}

/**
 * Everything a row needs to be sorted or filtered, without the caller knowing whether it
 * is a book, an episode or a download.
 *
 * The alternative is a sort function per screen, each reading its own row type, and the
 * duplication that comes with it. This keeps the *policy* — what "in progress" means,
 * what "recent" means — in one place, and leaves the screens to supply the facts.
 */
data class ListFacts(
    val title: String,
    val secondary: String? = null,
    val addedAtMs: Long = 0L,
    val publishedAtMs: Long = 0L,
    val durationSec: Double = 0.0,
    /** Bytes on the phone. Zero everywhere it does not apply, which is most places. */
    val sizeBytes: Long = 0L,
    val progressFraction: Float = 0f,
    val isFinished: Boolean = false,
    val isDownloaded: Boolean = false,
) {
    val isStarted: Boolean get() = progressFraction > 0f || isFinished
}

object ListControls {
    fun matches(facts: ListFacts, filter: ListFilter): Boolean = when (filter) {
        ListFilter.ALL -> true
        ListFilter.UNPLAYED -> !facts.isStarted
        ListFilter.IN_PROGRESS -> facts.progressFraction > 0f && !facts.isFinished
        ListFilter.FINISHED -> facts.isFinished
        ListFilter.DOWNLOADED -> facts.isDownloaded
    }

    /**
     * Case-insensitive substring match over the title and whatever the screen considers
     * secondary — the author for a book, the subtitle for an episode.
     *
     * Substring rather than the full-text index on purpose: this is filtering a list
     * already in memory, where "part of a word" is what someone typing into a box next
     * to the list expects, and where a round trip through SQLite would buy nothing.
     */
    fun matches(facts: ListFacts, query: String): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        return facts.title.lowercase().contains(needle) ||
            facts.secondary?.lowercase()?.contains(needle) == true
    }

    fun <T> sortItems(rows: List<T>, sort: ItemSort, facts: (T) -> ListFacts): List<T> = when (sort) {
        ItemSort.TITLE -> rows.sortedBy { facts(it).title.lowercase() }
        ItemSort.AUTHOR -> rows.sortedWith(
            compareBy({ facts(it).secondary?.lowercase() ?: "￿" }, { facts(it).title.lowercase() }),
        )
        ItemSort.ADDED -> rows.sortedByDescending { facts(it).addedAtMs }
        ItemSort.DURATION -> rows.sortedByDescending { facts(it).durationSec }
        // Furthest along first: the point of this ordering is finishing things.
        ItemSort.PROGRESS -> rows.sortedByDescending { facts(it).progressFraction }
        // Largest first: this ordering exists to answer "what is taking up the space".
        ItemSort.SIZE -> rows.sortedByDescending { facts(it).sizeBytes }
    }

    fun <T> sortEpisodes(rows: List<T>, sort: EpisodeSort, facts: (T) -> ListFacts): List<T> = when (sort) {
        EpisodeSort.NEWEST -> rows.sortedByDescending { facts(it).publishedAtMs }
        EpisodeSort.OLDEST -> rows.sortedBy { facts(it).publishedAtMs }
        EpisodeSort.LONGEST -> rows.sortedByDescending { facts(it).durationSec }
        EpisodeSort.SHORTEST -> rows.sortedBy { facts(it).durationSec }
        EpisodeSort.TITLE -> rows.sortedBy { facts(it).title.lowercase() }
    }
}
