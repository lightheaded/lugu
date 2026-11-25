package io.github.lightheaded.lugu

/** What the shell does about a lapsed sign-in, for the screen now on top. See [lapseOffer]. */
internal enum class LapseOffer {
    /** No lapse, or this lapse was answered already in this launch. */
    NONE,

    /** The player is on top. Ask again when the screen on top changes. */
    WAIT,

    /** Push the sign-in screen on top, and count the lapse as answered. */
    PUSH,

    /** The sign-in screen is on top already. Count the lapse as answered, and push nothing. */
    ALREADY_SHOWN,
}

/**
 * Whether to offer the sign-in for a lapse, given the route on top, the account that
 * lapsed, and the lapse already answered in this launch.
 *
 * The player is never covered. A listener who opened the player asked for the book that
 * plays, and a downloaded book plays through a lapse. A tap on the media notification after
 * the activity was gone showed why: the player opened at once, the lapse was read a moment
 * later, and the sign-in screen came up over the book that the tap asked for. So the offer
 * waits while the player is on top, and comes when the player closes. The same rule covers
 * a lapse that begins while somebody has the player open.
 */
internal fun lapseOffer(topRoute: String?, lapsedServerId: String?, answeredServerId: String?): LapseOffer = when {
    lapsedServerId == null || lapsedServerId == answeredServerId -> LapseOffer.NONE
    topRoute == Routes.LOGIN -> LapseOffer.ALREADY_SHOWN
    topRoute == Routes.PLAYER -> LapseOffer.WAIT
    // No destination yet: the graph is still being built at launch. Wait for the first one.
    topRoute == null -> LapseOffer.WAIT
    else -> LapseOffer.PUSH
}

/**
 * Whether the sign-in screen shows "Not now".
 *
 * Only on the screen that the lapse effect pushed ([pushedForLapse], its nav argument), and
 * only with an entry under it to return to. A sign-in somebody opened from the Accounts
 * screen is a choice, and Back is the way out of it. Deciding from the route under the
 * screen got both wrong: a lapse that began while Accounts was open had no button, because
 * Accounts was under it.
 */
internal fun offersNotNow(pushedForLapse: Boolean, hasEntryUnder: Boolean): Boolean =
    pushedForLapse && hasEntryUnder

/**
 * Whether a tap on the media notification may open the player over the screen on top.
 *
 * Any screen but a sign-in. Over a sign-in only the one the lapse effect pushed: a
 * downloaded book plays through a lapse, and the tap asked for it. Somebody adding an
 * account is in the middle of something, even while another account has lapsed.
 */
internal fun playerMayOpenOver(topRoute: String?, topIsLapseOffer: Boolean): Boolean =
    topRoute != Routes.LOGIN || topIsLapseOffer
