package io.github.lightheaded.lugu.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand

/**
 * The car's bookmark button, which marks the place now playing.
 *
 * A bookmark is the one thing a driver cannot do later. The moment worth marking passes
 * while both hands are on the wheel, and the phone is in a pocket. This is the button that
 * makes the feature reachable at the only time it is wanted.
 *
 * Android Auto's media template draws a custom action as its **icon** alone, so the icon
 * carries every fact the button states and the display name reaches only a screen reader
 * and a tooltip. [CarSpeedButton] sets out why, and why a drawable that Media3 ships is the
 * only kind a projection host can resolve. Media3 1.11.0 ships both halves of a bookmark
 * pair, `ICON_BOOKMARK_UNFILLED` and `ICON_BOOKMARK_FILLED`, so no drawable is added here.
 *
 * The two icons say whether the book already holds a bookmark. That is the whole feedback a
 * press can get in a car: the media template has no toast and no snackbar, and a button that
 * looks the same after a press as before it is the fault a driver reported against the speed
 * button. The first bookmark of a book therefore fills the icon, and the driver sees the
 * press land.
 *
 * A second bookmark in the same book changes nothing on screen, because the icon already
 * says what it can say. The alternative — filling the icon only while the position is near a
 * bookmark — moves with playback, so the button would flicker on its own twice a second
 * while nobody touched it. A stable icon that is true is worth more than a live one that
 * distracts.
 *
 * The button is for a book and never for a podcast episode. Audiobookshelf addresses a
 * bookmark by library item and gives an episode nowhere to put one, so the phone's player
 * hides the control for an episode as well (`PlayerViewModel.canBookmark`). A car gets the
 * same answer, and gets it by the button being absent rather than by a press that fails: a
 * driver has no way to read a failure. See `docs/BACKLOG.md` for the upstream request.
 *
 * Everything here is a pure function of one fact, so the choices can be held to in a test.
 */
@OptIn(UnstableApi::class)
object CarBookmarkButton {

    /**
     * The action a press of the bookmark button sends back.
     *
     * Named here rather than in [NotificationLayout], because no notification button
     * bookmarks anything: the phone has a player screen, where a bookmark can be named and
     * then found again. This button exists for the surface that has neither.
     */
    const val COMMAND_BOOKMARK_ADD = "io.github.lightheaded.lugu.BOOKMARK_ADD"

    /** Filled once the book holds a bookmark, so the first press of a book is visible. */
    fun iconFor(hasBookmarks: Boolean): Int = if (hasBookmarks) {
        CommandButton.ICON_BOOKMARK_FILLED
    } else {
        CommandButton.ICON_BOOKMARK_UNFILLED
    }

    /**
     * What a screen reader says.
     *
     * The action comes first, because that is what a press does. The state follows it, since
     * a spoken button has no icon beside it and the filled icon is otherwise unsaid.
     */
    fun labelFor(hasBookmarks: Boolean): String = if (hasBookmarks) {
        "Bookmark this place. This book already has bookmarks"
    } else {
        "Bookmark this place"
    }

    /**
     * The button for a book that has bookmarks, and the button for one that has none.
     *
     * Both are built once. There are two of them and nothing about either moves, so a cache
     * keyed on the rate — which [CarSpeedButton] needs — would only be a map with two
     * entries in it.
     */
    private val withBookmarks = build(hasBookmarks = true)
    private val withoutBookmarks = build(hasBookmarks = false)

    fun buttonFor(hasBookmarks: Boolean): CommandButton =
        if (hasBookmarks) withBookmarks else withoutBookmarks

    private fun build(hasBookmarks: Boolean): CommandButton =
        CommandButton.Builder(iconFor(hasBookmarks))
            .setSessionCommand(SessionCommand(COMMAND_BOOKMARK_ADD, Bundle.EMPTY))
            .setDisplayName(labelFor(hasBookmarks))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
}
