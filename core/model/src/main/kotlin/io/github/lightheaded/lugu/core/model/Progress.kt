package io.github.lightheaded.lugu.core.model

/**
 * Listening progress for one (item, episode) pair and one user.
 *
 * [lastUpdateMs] is written by whoever produced the value, on *their* clock: the
 * server's `lastUpdate` on a value read from the API, and this device's clock on a
 * value read out of Room. The two are not comparable, and comparing them is what put a
 * resumed book thirty seconds behind where it was left — see [ProgressConflictResolver].
 */
data class MediaProgress(
    val libraryItemId: String,
    val episodeId: String? = null,
    val currentTimeSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val progress: Double = 0.0,
    val isFinished: Boolean = false,
    val lastUpdateMs: Long = 0L,
    val startedAtMs: Long = 0L,
) {
    val key: ProgressKey get() = ProgressKey(libraryItemId, episodeId)
}

data class ProgressKey(val libraryItemId: String, val episodeId: String?)

/**
 * What this device already knows about the server's copy of one row.
 *
 * It exists so that a server timestamp is only ever compared with another server
 * timestamp. [lastUpdateMs] is the server's own `lastUpdate` for the copy this device
 * last read, and [pushedTimeSec] is the position the server last accepted *from here*.
 *
 * The second field is needed because `PATCH /api/me/progress/:id` answers with an empty
 * body. The server stamps the row with a new `lastUpdate` that lugu never gets to see,
 * so after a successful push the stored stamp is always behind — and "the stamp moved"
 * on its own therefore cannot mean "somebody else wrote this". What the push does tell
 * us is the *position* the server now holds, and that is enough to recognise our own
 * copy coming back.
 */
data class ServerCopySeen(
    /** The server's `lastUpdate` for the copy last read. 0 when no copy was ever read. */
    val lastUpdateMs: Long = 0L,
    /** The position the server last accepted from here. Null when nothing was ever pushed. */
    val pushedTimeSec: Double? = null,
    /** The finished flag that went with [pushedTimeSec]. */
    val pushedFinished: Boolean = false,
)

/** What the sync engine decided to do when local and server progress disagree. */
sealed interface ProgressResolution {
    /** Local is authoritative; push it. */
    data class KeepLocal(val local: MediaProgress) : ProgressResolution

    /** Server is newer; adopt it. [replacedLocal] is retained so the UI can offer an undo. */
    data class AdoptServer(
        val server: MediaProgress,
        val replacedLocal: MediaProgress?,
        val jumpSeconds: Double,
    ) : ProgressResolution

    /** Nothing to do — the two agree closely enough. */
    data object InSync : ProgressResolution
}

/**
 * Pull-before-push, the rule that fixes lugu's #1 trust-killer (official app #1022,
 * #1059, #1161, #1182: stale local progress overwriting newer server progress).
 *
 * Pure function so it can be property-tested without a server or a database.
 *
 * **One clock per comparison.** The question this answers is "did the server's copy come
 * from somewhere other than here", and the only clock both sides share is the server's.
 * So a server stamp is compared with the last server stamp this device read, never with
 * a local one. The first version of this compared the server's `lastUpdate` with a
 * `System.currentTimeMillis()` written by this device, which decides the winner by clock
 * skew rather than by who listened last: with the server's clock ahead, a stale server
 * position won every conflict and a resumed book came back behind where it was left.
 * `ProcessDeathResumptionTest` caught it as "the book resumed 30056ms behind".
 *
 * @param materialJumpSec how far apart two positions must be before adopting the
 *   server's counts as a user-visible jump worth an undo affordance.
 */
object ProgressConflictResolver {
    const val DEFAULT_MATERIAL_JUMP_SEC: Double = 15.0

    /**
     * How close a server position has to be to the one this device pushed to count as
     * the same value coming back.
     *
     * Well below [DEFAULT_MATERIAL_JUMP_SEC], so it can never swallow a real
     * disagreement, and comfortably above anything the server's own rounding of a
     * JSON number can introduce.
     */
    const val ECHO_TOLERANCE_SEC: Double = 2.0

    fun resolve(
        local: MediaProgress?,
        server: MediaProgress?,
        seen: ServerCopySeen = ServerCopySeen(),
        materialJumpSec: Double = DEFAULT_MATERIAL_JUMP_SEC,
    ): ProgressResolution {
        if (server == null) return local?.let { ProgressResolution.KeepLocal(it) } ?: ProgressResolution.InSync
        if (local == null) {
            return ProgressResolution.AdoptServer(server, null, server.currentTimeSec)
        }

        val delta = server.currentTimeSec - local.currentTimeSec
        if (kotlin.math.abs(delta) < materialJumpSec && server.isFinished == local.isFinished) {
            return ProgressResolution.InSync
        }

        // The server holds something this device did not put there: it came from another
        // device, so it wins. Never the other way round without user action.
        return if (serverCopyCameFromElsewhere(server, seen)) {
            ProgressResolution.AdoptServer(server, local, delta)
        } else {
            ProgressResolution.KeepLocal(local)
        }
    }

    /**
     * Guard for automatic pushes: an unattended push may only overwrite a server copy
     * that this device is already accounted for. Anything else needs an explicit user
     * action (`force`), because it would drop another device's listening.
     *
     * This is the same predicate [resolve] uses, which is the point — a resolution that
     * says "keep local" and a guard that then refuses the push would leave the position
     * stranded on the phone forever.
     */
    fun mayPushAutomatically(server: MediaProgress?, seen: ServerCopySeen): Boolean {
        if (server == null) return true
        return !serverCopyCameFromElsewhere(server, seen)
    }

    /**
     * Whether the server's copy is one this device has not already accounted for.
     *
     * Two ways it is ours. The stamp is the one we last read, so nothing has touched the
     * row since we looked; or the position is the one the server accepted from us, which
     * is how a push we were never given a stamp for is recognised on the way back.
     *
     * Public because the login sweep asks the same question of a dirty row as playback
     * does, and two spellings of one rule is how the clocks got mixed in the first place.
     */
    fun serverCopyCameFromElsewhere(server: MediaProgress, seen: ServerCopySeen): Boolean {
        if (seen.lastUpdateMs != 0L && server.lastUpdateMs == seen.lastUpdateMs) return false
        val pushed = seen.pushedTimeSec ?: return true
        val echoes = kotlin.math.abs(server.currentTimeSec - pushed) < ECHO_TOLERANCE_SEC &&
            server.isFinished == seen.pushedFinished
        return !echoes
    }
}
