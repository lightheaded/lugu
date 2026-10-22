package io.github.lightheaded.lugu.feature.player

import io.github.lightheaded.lugu.playback.NowPlaying
import io.github.lightheaded.lugu.playback.StartingItem

/** The text the credit slot shows while a requested item is still starting. */
internal const val STARTING_LABEL = "Starting…"

/**
 * What lugu knows from Room about an item that a play request is starting.
 *
 * Read from the local mirror, so it is there with no signal. Any field can be missing: an
 * episode that the mirror does not hold has no title of its own, and then the show's title
 * stands in for it.
 */
data class StartingDetails(
    val libraryItemId: String,
    val episodeId: String?,
    val title: String?,
    val coverUrl: String?,
    val durationSec: Double,
)

/**
 * What the title area of the player and the mini player shows.
 *
 * One value for both, so the two can never disagree about which item is on screen. The
 * fields map to fixed slots: the cover, the title line, the credit line under it, and the
 * seek bar's length. A starting item fills the same slots as a loaded one, so nothing on
 * screen moves when it starts or when it has loaded.
 */
data class PlayerHeader(
    val title: String,
    val credit: String?,
    val coverUrl: String?,
    val titleLink: PlayerLink?,
    val authorLink: PlayerLink?,
    /** True while the player still holds an earlier item and this one is on its way. */
    val isStarting: Boolean,
    /** The length the seek bar shows while starting. Ignored for a loaded item. */
    val startingDurationSec: Double = 0.0,
)

/**
 * Whether [starting] asks for something other than what the player holds.
 *
 * A request for the item that is already loaded (a replay, or a press on the same row) is not
 * news: the screen goes on showing the loaded item and its position.
 */
internal fun startsSomethingElse(now: NowPlaying?, starting: StartingItem?): Boolean =
    starting != null &&
        (now == null || now.libraryItemId != starting.libraryItemId || now.episodeId != starting.episodeId)

/**
 * Chooses what the title area shows.
 *
 * On a bad network a play request can take many seconds, and the player still holds the
 * previous item for all of that time. Before this, the player opened on that previous item,
 * so the listener saw the book they had just chosen to leave. Now the requested item is on
 * screen from the press: its title and cover from Room, and "Starting…" in the credit line.
 *
 * [details] count only when they are for the item that [starting] names. Until Room answers,
 * the title is blank rather than the old item's.
 */
internal fun playerHeader(
    now: NowPlaying?,
    starting: StartingItem?,
    details: StartingDetails?,
): PlayerHeader {
    if (starting != null && startsSomethingElse(now, starting)) {
        val known = details?.takeIf {
            it.libraryItemId == starting.libraryItemId && it.episodeId == starting.episodeId
        }
        return PlayerHeader(
            title = known?.title.orEmpty(),
            credit = STARTING_LABEL,
            coverUrl = known?.coverUrl,
            titleLink = titleLink(starting.libraryItemId, starting.episodeId),
            authorLink = null,
            isStarting = true,
            startingDurationSec = known?.durationSec ?: 0.0,
        )
    }
    if (now == null) {
        return PlayerHeader(
            title = "Nothing playing",
            credit = null,
            coverUrl = null,
            titleLink = null,
            authorLink = null,
            isStarting = false,
        )
    }
    return PlayerHeader(
        title = now.title,
        credit = now.author,
        coverUrl = now.coverUrl,
        titleLink = now.titleLink,
        authorLink = now.authorLink,
        isStarting = false,
    )
}
