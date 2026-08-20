package io.github.lightheaded.lugu.playback

import io.github.lightheaded.lugu.core.db.episodeKeyOf

/**
 * A node in the car's browse tree, as an id and back again.
 *
 * The ids are strings because that is all a media browser carries — a car hands back
 * exactly what it was given, possibly after the process has died, so an id has to be
 * enough to answer on its own.
 *
 * Everything after a prefix is taken verbatim, which is why nothing here is escaped: a
 * series called "Wool / Shift / Dust" survives being a node id because the slashes are
 * never looked for.
 */
sealed interface BrowseNode {
    data object Root : BrowseNode

    /**
     * The second root, served only to a host that asks for suggestions.
     *
     * A host reaches it by setting `EXTRA_SUGGESTED` in its root hints, and it holds what
     * [Continue] holds. It is a root and never a child, so nothing browses into it — see
     * `BrowseTree.suggestedRoot`.
     */
    data object Suggested : BrowseNode

    data object Continue : BrowseNode

    data object UpNext : BrowseNode

    data object Downloaded : BrowseNode

    data object AllSeries : BrowseNode

    data object AllPodcasts : BrowseNode

    /** The newest unplayed episode of everything being followed, across podcasts. */
    data object LatestEpisodes : BrowseNode

    data object Libraries : BrowseNode

    data class Series(val title: String) : BrowseNode

    data class Podcast(val itemId: String) : BrowseNode

    data class Library(val libraryId: String) : BrowseNode

    /** A leaf: something to play. */
    data class Playable(val itemId: String, val episodeId: String?) : BrowseNode

    /** An id that did not come from us, or one we no longer serve. */
    data object Unknown : BrowseNode

    val id: String
        get() = when (this) {
            Root -> ROOT
            Suggested -> SUGGESTED_ROOT
            Continue -> CONTINUE
            UpNext -> UP_NEXT
            Downloaded -> DOWNLOADED
            AllSeries -> ALL_SERIES
            AllPodcasts -> ALL_PODCASTS
            LatestEpisodes -> LATEST_EPISODES
            Libraries -> LIBRARIES
            is Series -> "$SERIES_PREFIX$title"
            is Podcast -> "$PODCAST_PREFIX$itemId"
            is Library -> "$LIBRARY_PREFIX$libraryId"
            is Playable -> "$PLAY_PREFIX$itemId$PLAY_SEPARATOR${episodeKeyOf(episodeId)}"
            Unknown -> UNKNOWN
        }

    companion object {
        const val ROOT = "lugu/root"

        /**
         * A different id from [ROOT], because a host must be able to tell the two apart.
         *
         * A browser caches children against the id it was given. One id for both roots
         * would make the suggestions and the browse tree overwrite each other.
         */
        const val SUGGESTED_ROOT = "lugu/suggested"

        private const val CONTINUE = "lugu/continue"
        private const val UP_NEXT = "lugu/up-next"
        private const val DOWNLOADED = "lugu/downloaded"
        private const val ALL_SERIES = "lugu/series"
        private const val ALL_PODCASTS = "lugu/podcasts"
        private const val LATEST_EPISODES = "lugu/latest-episodes"
        private const val LIBRARIES = "lugu/libraries"
        private const val UNKNOWN = "lugu/unknown"

        private const val SERIES_PREFIX = "lugu/series/"
        private const val PODCAST_PREFIX = "lugu/podcast/"
        private const val LIBRARY_PREFIX = "lugu/library/"
        private const val PLAY_PREFIX = "lugu/play/"

        /**
         * A vertical bar, not a slash: an episode id is opaque and a slash inside one
         * would make the split ambiguous. This character does not occur in the ids
         * Audiobookshelf issues, and it is the same separator the download cache keys
         * already use.
         */
        private const val PLAY_SEPARATOR = "|"

        fun parse(id: String): BrowseNode = when {
            id == ROOT -> Root
            id == SUGGESTED_ROOT -> Suggested
            id == CONTINUE -> Continue
            id == UP_NEXT -> UpNext
            id == DOWNLOADED -> Downloaded
            id == ALL_SERIES -> AllSeries
            id == ALL_PODCASTS -> AllPodcasts
            id == LATEST_EPISODES -> LatestEpisodes
            id == LIBRARIES -> Libraries
            id.startsWith(SERIES_PREFIX) ->
                id.removePrefix(SERIES_PREFIX).takeIf { it.isNotEmpty() }?.let(::Series) ?: Unknown

            id.startsWith(PODCAST_PREFIX) ->
                id.removePrefix(PODCAST_PREFIX).takeIf { it.isNotEmpty() }?.let(::Podcast) ?: Unknown

            id.startsWith(LIBRARY_PREFIX) ->
                id.removePrefix(LIBRARY_PREFIX).takeIf { it.isNotEmpty() }?.let(::Library) ?: Unknown

            id.startsWith(PLAY_PREFIX) -> parsePlayable(id.removePrefix(PLAY_PREFIX))
            else -> Unknown
        }

        private fun parsePlayable(rest: String): BrowseNode {
            val itemId = rest.substringBefore(PLAY_SEPARATOR)
            if (itemId.isEmpty() || !rest.contains(PLAY_SEPARATOR)) return Unknown
            return Playable(itemId, rest.substringAfter(PLAY_SEPARATOR).takeIf { it.isNotEmpty() })
        }
    }
}
