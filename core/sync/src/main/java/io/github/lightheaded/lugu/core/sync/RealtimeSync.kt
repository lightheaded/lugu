package io.github.lightheaded.lugu.core.sync

import io.github.lightheaded.lugu.core.model.ProgressKey
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What one [RealtimeEvent] asks of the mirror.
 *
 * Separating the decision from the doing is what makes the interesting half testable:
 * "which of these events causes a re-fetch, and of what" is a rule worth pinning, and
 * it needs neither a server nor a database to check.
 */
internal sealed interface RealtimeWork {
    data class FetchItems(val itemIds: List<String>) : RealtimeWork

    data class RemoveItems(val itemIds: List<String>) : RealtimeWork

    data object FetchLibraries : RealtimeWork

    data class FetchProgress(val libraryItemId: String, val episodeId: String?) : RealtimeWork

    data object ReconcileProgress : RealtimeWork

    data object Nothing : RealtimeWork
}

/**
 * The rules that turn a hint into work.
 *
 * Two of them carry the weight. First, an event is never written to the mirror as if it
 * were a row: the server's `item_updated` payload is an item in the old expanded shape,
 * which is close to what the API returns but not identical to it, and a partial parse
 * that writes a half-populated row over a complete one is worse than no live updates at
 * all. Fetching the item by id is one small request and is right by construction.
 *
 * Second, progress for whatever is loaded in the player is left alone entirely. The
 * server echoes `user_item_progress_updated` back to the device that caused it, so
 * acting on it during playback would mean the app racing its own writes; and a remote
 * position may only reach a playing item through [ProgressRepository.startSession],
 * which resolves the conflict and hands back a [ProgressJump] the UI can show and
 * offer to undo. There is no way to honour that contract from here, so the correct
 * answer is to do nothing and let the next session start pick it up.
 */
internal object RealtimePlanner {
    /**
     * A scan on the server emits `items_updated` in chunks for the whole library. Past
     * this, re-fetching item by item costs more than it saves and the periodic sweep is
     * the better tool, so the batch is dropped rather than walked.
     */
    const val MAX_ITEMS_PER_EVENT = 25

    fun plan(event: RealtimeEvent, playing: ProgressKey?): RealtimeWork = when (event) {
        is RealtimeEvent.ItemsChanged ->
            if (event.itemIds.size > MAX_ITEMS_PER_EVENT) {
                RealtimeWork.Nothing
            } else {
                RealtimeWork.FetchItems(event.itemIds)
            }

        is RealtimeEvent.ItemsRemoved -> RealtimeWork.RemoveItems(event.itemIds)

        RealtimeEvent.LibrariesChanged -> RealtimeWork.FetchLibraries

        is RealtimeEvent.ProgressChanged ->
            if (playing != null &&
                playing.libraryItemId == event.libraryItemId &&
                playing.episodeId == event.episodeId
            ) {
                RealtimeWork.Nothing
            } else {
                RealtimeWork.FetchProgress(event.libraryItemId, event.episodeId)
            }

        RealtimeEvent.UserChanged -> RealtimeWork.ReconcileProgress
    }
}

/**
 * A ceiling on how much work the socket may cause.
 *
 * A library scan, a bulk metadata edit or a match-all run emits an event per item, and
 * a client that answers each with a request turns somebody else's housekeeping into a
 * request storm from a phone. Past the ceiling the events are dropped: the sweep is
 * still there, still correct, and better suited to that much change anyway.
 */
internal class RealtimeRateLimit(
    private val maxPerWindow: Int,
    private val windowMs: Long,
) {
    private var windowStartMs = 0L
    private var used = 0

    @Synchronized
    fun allow(nowMs: Long): Boolean {
        if (nowMs - windowStartMs >= windowMs) {
            windowStartMs = nowMs
            used = 0
        }
        if (used >= maxPerWindow) return false
        used += 1
        return true
    }
}

/**
 * Applies what the socket says to the local mirror.
 *
 * Everything here goes through the same write paths the periodic sync uses, for the
 * same reason those paths exist: they are where the conflict rules live. Progress in
 * particular is re-read from the API and resolved by `ProgressConflictResolver` rather
 * than taken from the event, so a position pushed from another device still cannot
 * silently replace a newer local one — the bug M0 was built to prevent.
 *
 * None of this is load-bearing. Every failure is swallowed, because the poll-and-sweep
 * sync behind it already covers all of it and a live update that did not arrive is not
 * something a listener can act on.
 */
@Singleton
class RealtimeSync @Inject constructor(
    private val realtime: Realtime,
    private val authRepository: AuthRepository,
    private val libraryRepository: LibraryRepository,
    private val progressRepository: ProgressRepository,
    private val clock: Clock,
) {
    private val started = AtomicBoolean(false)
    private val playing = AtomicReference<ProgressKey?>(null)
    private val itemFetches = RealtimeRateLimit(MAX_ITEM_FETCHES_PER_WINDOW, RATE_WINDOW_MS)
    private val progressFetches = RealtimeRateLimit(MAX_PROGRESS_FETCHES_PER_WINDOW, RATE_WINDOW_MS)

    /**
     * What the player is on, so its progress can be left alone. Null when nothing is
     * loaded. Set from the application, which is the only place that can see both the
     * player and this.
     */
    fun setNowPlaying(libraryItemId: String?, episodeId: String?) {
        playing.set(libraryItemId?.let { ProgressKey(it, episodeId) })
    }

    /** Safe to call more than once; only the first call does anything. */
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            realtime.events.collect { event ->
                try {
                    apply(RealtimePlanner.plan(event, playing.get()))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // One failed hint is not worth stopping the rest for, and there is
                    // nothing to tell the listener: the sweep covers the same ground.
                }
            }
        }
    }

    private suspend fun apply(work: RealtimeWork) {
        val account = authRepository.account() ?: return
        when (work) {
            is RealtimeWork.FetchItems -> work.itemIds.forEach { itemId ->
                if (itemFetches.allow(clock.nowMs())) {
                    libraryRepository.syncItemDetail(account, itemId)
                }
            }

            is RealtimeWork.RemoveItems -> work.itemIds.forEach { remove(account, it) }

            RealtimeWork.FetchLibraries -> libraryRepository.syncLibraries(account)

            is RealtimeWork.FetchProgress ->
                if (progressFetches.allow(clock.nowMs())) {
                    // Pull-before-push, the same call a session start makes: the position
                    // is read back from the API and put through ProgressConflictResolver
                    // rather than taken from the event. The jump it returns is dropped on
                    // purpose — nothing is playing this item, so there is no position to
                    // correct and nobody to offer an undo to.
                    progressRepository.startSession(account, work.libraryItemId, work.episodeId)
                }

            RealtimeWork.ReconcileProgress ->
                if (progressFetches.allow(clock.nowMs())) progressRepository.reconcile(account)

            RealtimeWork.Nothing -> Unit
        }
    }

    /**
     * Forgets one item through the repository, which knows everything that has to be
     * forgotten with it — the row, the search index entry that would otherwise point at
     * nothing, the episodes and the chapters.
     */
    private suspend fun remove(account: ActiveAccount, itemId: String) {
        libraryRepository.remove(account, itemId)
    }

    private companion object {
        const val RATE_WINDOW_MS = 60_000L
        const val MAX_ITEM_FETCHES_PER_WINDOW = 60
        const val MAX_PROGRESS_FETCHES_PER_WINDOW = 30
    }
}
