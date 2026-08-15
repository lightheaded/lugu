package io.github.lightheaded.lugu.core.model

/**
 * A named place in a book.
 *
 * [timeSec] is whole seconds and is the identity — Audiobookshelf addresses a bookmark by
 * `(item, time)` and gives it no id of its own. Two bookmarks a fraction of a second apart
 * are therefore the same bookmark, which is why the position is rounded on the way in
 * rather than at the point of display.
 *
 * [isPending] means the server has not confirmed it yet. It is shown, because a bookmark
 * made in a tunnel is a real bookmark and hiding it would look like the button failed.
 */
data class Bookmark(
    val libraryItemId: String,
    val timeSec: Long,
    val title: String,
    val createdAtMs: Long,
    val isPending: Boolean = false,
)
