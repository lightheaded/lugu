package io.github.lightheaded.lugu.core.model

/**
 * Listening progress for one (item, episode) pair and one user.
 *
 * [lastUpdateMs] is the server's `lastUpdate` and is the ordering key for conflict
 * resolution — see [ProgressResolution]. The server does last-write-wins with no
 * conflict handling of its own, so the client has to be the careful one.
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
 * @param materialJumpSec how far apart two positions must be before adopting the
 *   server's counts as a user-visible jump worth an undo affordance.
 */
object ProgressConflictResolver {
    const val DEFAULT_MATERIAL_JUMP_SEC: Double = 15.0

    fun resolve(
        local: MediaProgress?,
        server: MediaProgress?,
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

        // The server's update is newer than anything we know locally: it came from
        // another device, so it wins. Never the other way round without user action.
        return if (server.lastUpdateMs > local.lastUpdateMs) {
            ProgressResolution.AdoptServer(server, local, delta)
        } else {
            ProgressResolution.KeepLocal(local)
        }
    }

    /**
     * Guard for automatic pushes: an unattended push may only move the server forward
     * in update time. Backwards jumps require an explicit user action (`force`).
     */
    fun mayPushAutomatically(local: MediaProgress, knownServer: MediaProgress?): Boolean {
        if (knownServer == null) return true
        return local.lastUpdateMs >= knownServer.lastUpdateMs
    }
}
