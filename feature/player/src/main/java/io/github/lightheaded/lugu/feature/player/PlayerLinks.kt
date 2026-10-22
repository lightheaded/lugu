package io.github.lightheaded.lugu.feature.player

import io.github.lightheaded.lugu.core.sync.BrowseKind
import io.github.lightheaded.lugu.playback.NowPlaying

/**
 * Where a tap on the player leads.
 *
 * The player knows what is playing but not the routes of the app, so it names a page and
 * the nav graph turns the name into a route. Kept as a value, not as a lambda call inside
 * the layout, so that a test can ask "where does this tap go" without a Hilt view model.
 */
sealed interface PlayerLink {
    /** The page of a book or of a podcast show. */
    data class Item(val itemId: String) : PlayerLink

    /** The page of one podcast episode. */
    data class Episode(val itemId: String, val episodeId: String) : PlayerLink

    /** A browse group: an author, a narrator or a series, by [BrowseKind.id]. */
    data class Group(val kind: String, val name: String) : PlayerLink
}

/**
 * The cover and the title lead to the thing that plays.
 *
 * For an episode that is the episode page, not the show. The show page made the listener
 * find the episode again in a list of hundreds (issue #2).
 */
internal fun titleLink(itemId: String, episodeId: String?): PlayerLink =
    if (episodeId != null) PlayerLink.Episode(itemId, episodeId) else PlayerLink.Item(itemId)

/**
 * The line under the title leads to the credit it shows.
 *
 * For a book the line is the author, so it opens the author's page — the same page the
 * author link on the item page opens, found by the same string. For an episode the line
 * names the show's creator, and the useful page is the show itself: an author page for a
 * podcast holds one item, the show, so it is one tap more for nothing.
 *
 * No line, no link.
 */
internal fun authorLink(itemId: String, episodeId: String?, author: String?): PlayerLink? = when {
    author.isNullOrBlank() -> null
    episodeId != null -> PlayerLink.Item(itemId)
    else -> PlayerLink.Group(BrowseKind.AUTHORS.id, author)
}

internal val NowPlaying.titleLink: PlayerLink get() = titleLink(libraryItemId, episodeId)

internal val NowPlaying.authorLink: PlayerLink? get() = authorLink(libraryItemId, episodeId, author)
