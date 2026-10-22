package io.github.lightheaded.lugu.playback

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** What became of one playback resumption. */
internal sealed interface ResumptionOutcome {
    /** Nothing else reached the player, so the last item goes in. */
    data class Resume(val resumption: Resumption) : ResumptionOutcome

    /** Nothing to resume: no account, no progress row, or an item that did not resolve. */
    data object NothingToResume : ResumptionOutcome

    /** A play request, or a pause, came from the app while this was resolving. It wins. */
    data object Superseded : ResumptionOutcome

    /** The player got an item from somewhere else while this was resolving. It stays. */
    data object AlreadyLoaded : ResumptionOutcome
}

/**
 * Decides whether a playback resumption may load the last item played.
 *
 * Media3 asks for a resumption when a play command reaches a player that holds nothing: a
 * headset button, the system's media controls, a car. Resolving the last item can take a
 * play session from the server, and Media3 then loads whatever the answer holds and plays
 * it, with no further check. A play request from the app can arrive inside that time. The
 * request is the listener choosing something, and the resumption is a guess at what they
 * want, so the request must win. Without this, the last item replaced the one the listener
 * had just pressed — the same "it opens the old item" fault that [PlayStarter] fixes for
 * two requests, through a second way in.
 *
 * Two facts are checked after the resolution, because nothing suspends between that check
 * and the load that Media3 does when the future completes:
 *
 *  - **Did a request or a pause come in?** [PlayRequests.generation] moves for both. A
 *    request that is still starting has not reached the player yet, so a check of the
 *    player alone does not see it. A pause in the same window means "stop", and a
 *    resumption that played anyway would make the pause button lie.
 *  - **Does the player hold an item now?** Something else loaded one: a request that
 *    finished, an arming, a car.
 *
 * A request that is starting when the resumption begins, or that began during it, is
 * waited for, up to [startWaitMs]. Media3 presses play on the player when the future
 * completes, and a press on a player that still holds nothing prepares an empty playlist,
 * which ends at once. The wait lets the press land on the item the request loads.
 */
internal class ResumptionGate(
    private val requests: PlayRequests,
    private val playerHoldsItem: () -> Boolean,
    private val resolveLastPlayed: suspend () -> Resumption?,
    private val startWaitMs: Long = START_WAIT_MS,
) {
    suspend fun decide(): ResumptionOutcome {
        val before = requests.generation()
        if (requests.starting.value != null) {
            awaitStart()
            return ResumptionOutcome.Superseded
        }

        val resolved = resolveLastPlayed()

        if (requests.generation() != before) {
            awaitStart()
            return ResumptionOutcome.Superseded
        }
        if (playerHoldsItem()) return ResumptionOutcome.AlreadyLoaded
        return resolved?.let { ResumptionOutcome.Resume(it) } ?: ResumptionOutcome.NothingToResume
    }

    private suspend fun awaitStart() {
        withTimeoutOrNull(startWaitMs) { requests.starting.first { it == null } }
    }

    companion object {
        /**
         * How long a resumption waits for a request that is still starting.
         *
         * A start on a bad network can wait on several read timeouts. Past this, the
         * resumption gives up and leaves the player alone all the same.
         */
        const val START_WAIT_MS = 60_000L
    }
}
