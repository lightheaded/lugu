package io.github.lightheaded.lugu.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.db.DownloadEntity
import io.github.lightheaded.lugu.core.download.DownloadRepository
import io.github.lightheaded.lugu.core.download.DownloadStatus
import io.github.lightheaded.lugu.core.model.MediaProgress
import io.github.lightheaded.lugu.core.model.PodcastEpisode
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.LibraryRepository
import io.github.lightheaded.lugu.core.sync.PlaybackPrefs
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import io.github.lightheaded.lugu.core.sync.QueueRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EpisodeUiState(
    val showTitle: String = "",
    /**
     * The show this episode belongs to. Known from the route, so the way to the show works
     * before the episode has loaded and after a feed has withdrawn it.
     */
    val showItemId: String? = null,
    val episode: PodcastEpisode? = null,
    /** The show's cover. Coil's cache serves it offline once it has been seen. */
    val coverUrl: String? = null,
    val progressFraction: Float = 0f,
    val positionSec: Double = 0.0,
    val isFinished: Boolean = false,
    val download: DownloadStatus? = null,
    /**
     * False until the sync attempt has ended. A missing episode is only said to be gone
     * after that: before it, the row may simply not have arrived yet.
     */
    val loaded: Boolean = false,
    val message: String? = null,
)

/**
 * A change to one episode that has happened and can still be taken back.
 *
 * [Download] with a [Download.deferred] row is a completed download marked for deletion:
 * nothing has left disk, so undo is an exact restore. With no row it was a download still
 * running, which is cancelled at once, and undo asks for it again.
 *
 * [Progress] keeps the progress from before a finished mark, so undo puts the position
 * back too, not only the flag. [Progress.after] is the row's `lastUpdateMs` right after the
 * mark: undo applies only while nothing newer (playback, a sync) has written the row.
 */
internal sealed class RowUndo(val text: String) {
    class Download(
        val account: ActiveAccount,
        val itemId: String,
        val episodeId: String,
        val deferred: DownloadEntity?,
        text: String,
    ) : RowUndo(text)

    class Progress(
        val itemId: String,
        val episodeId: String,
        val before: MediaProgress?,
        val after: Long?,
        text: String,
    ) : RowUndo(text)
}

/**
 * The undo offer for a screen of episode rows: one change at a time, as on the podcast page.
 *
 * Shared by the episode page and the episodes view, so both keep the same promise. A newer
 * change settles the older one first, as a timeout would.
 */
internal class RowUndos(
    private val scope: CoroutineScope,
    private val authRepository: AuthRepository,
    private val downloadRepository: DownloadRepository,
    private val progressRepository: ProgressRepository,
    /** Where a refusal goes when an undo cannot ask for a download again. */
    private val onMessage: (String) -> Unit,
) {
    private val pending = MutableStateFlow<RowUndo?>(null)

    val current: StateFlow<RowUndo?> = pending

    /** Deletes a completed download, or cancels one still running, and offers Undo. */
    suspend fun removeDownload(
        account: ActiveAccount,
        itemId: String,
        episodeId: String,
        download: DownloadStatus?,
    ) {
        if (download == null) return
        if (download.isComplete) {
            val row = downloadRepository.deferDelete(account, itemId, episodeId) ?: return
            offer(RowUndo.Download(account, itemId, episodeId, row, "Download removed"))
        } else {
            downloadRepository.remove(account, itemId, episodeId)
            offer(RowUndo.Download(account, itemId, episodeId, null, "Download cancelled"))
        }
    }

    /** Sets the finished mark, keeping the progress from before it for Undo. */
    suspend fun setFinished(
        account: ActiveAccount,
        itemId: String,
        episodeId: String,
        isFinished: Boolean,
        durationSec: Double,
    ) {
        val before = progressRepository.snapshot(account, itemId, episodeId)
        progressRepository.setFinished(account, itemId, episodeId, isFinished, durationSec)
        val after = progressRepository.snapshot(account, itemId, episodeId)?.lastUpdateMs
        offer(
            RowUndo.Progress(
                itemId,
                episodeId,
                before,
                after,
                if (isFinished) "Marked as finished" else "Marked as not finished",
            ),
        )
    }

    /** Swaps the offer first, so an Undo tap on the old notice cannot win while it settles. */
    private fun offer(next: RowUndo) {
        val older = pending.getAndUpdate { next }
        older?.let { settle(it) }
    }

    /**
     * Puts back exactly what [shown] took away, if it is still the pending change. A tap on
     * an old notice after a newer change does nothing, rather than undo the newer one.
     */
    fun undo(shown: RowUndo) {
        if (!pending.compareAndSet(shown, null)) return
        scope.launch {
            val account = authRepository.account() ?: return@launch
            when (shown) {
                is RowUndo.Download -> if (shown.deferred != null) {
                    downloadRepository.restoreDeferred(account, shown.deferred)
                } else {
                    downloadRepository.download(account, shown.itemId, shown.episodeId)
                        .onFailure { onMessage(it.message ?: "Could not start the download") }
                }

                // False when something newer wrote the row; the newer state stands.
                is RowUndo.Progress -> progressRepository.restore(
                    account,
                    shown.itemId,
                    shown.episodeId,
                    shown.before,
                    ifUnchangedSince = shown.after,
                )
            }
        }
    }

    /** Lets [shown] stand once its notice closes with no undo. */
    fun dismiss(shown: RowUndo) {
        if (!pending.compareAndSet(shown, null)) return
        settle(shown)
    }

    /** Playback of this item started here, so a finished-mark undo would fight it. */
    fun dropProgressUndo(itemId: String, episodeId: String?) {
        val last = pending.value as? RowUndo.Progress ?: return
        if (last.itemId != itemId || (episodeId != null && last.episodeId != episodeId)) return
        pending.compareAndSet(last, null)
    }

    /**
     * The screen is going away with an undo still on offer: the change stands, and a
     * deferred delete is finished on a scope that outlives the view model.
     */
    fun settleOnClose() {
        val last = pending.getAndUpdate { null } ?: return
        val row = (last as? RowUndo.Download)?.deferred ?: return
        downloadRepository.finalizeDeferredLater(last.account, listOf(row))
    }

    /** Only a deferred delete has work left: its files are still on disk until now. */
    private fun settle(last: RowUndo) {
        if (last !is RowUndo.Download) return
        val row = last.deferred ?: return
        scope.launch { downloadRepository.finalizeDeferred(last.account, row) }
    }
}

/** How long an Undo stays offered: the same setting as the player's own notices. */
internal fun noticeMillisOf(playbackPrefs: PlaybackPrefs) =
    playbackPrefs.settings.map { it.noticeSeconds.coerceAtLeast(1) * 1_000L }

/**
 * One episode, and everything needed to decide whether to hear it.
 *
 * Reads from the mirror like every other page, so an episode named in a notification opens
 * with its show notes on a phone with no signal — the sync that found the episode wrote its
 * description at the same time.
 *
 * The episode itself is read once rather than observed. A description does not change while
 * somebody is reading it, and the two facts that do move — the position and the download —
 * arrive as flows of their own. The one-shot read is taken again when the detail sync
 * finishes, which covers the only case where the row is not there yet: a link followed into
 * a podcast this phone has never opened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EpisodeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
    private val libraryRepository: LibraryRepository,
    private val progressRepository: ProgressRepository,
    private val downloadRepository: DownloadRepository,
    private val queueRepository: QueueRepository,
    playbackPrefs: PlaybackPrefs,
) : ViewModel() {

    private val itemId: String = checkNotNull(savedStateHandle["itemId"])

    private val episodeId: String = checkNotNull(savedStateHandle["episodeId"])

    private val episode = MutableStateFlow<PodcastEpisode?>(null)

    private val loaded = MutableStateFlow(false)

    private val message = MutableStateFlow<String?>(null)

    private val undos = RowUndos(
        viewModelScope,
        authRepository,
        downloadRepository,
        progressRepository,
        onMessage = { message.value = it },
    )

    /** The last change that can be taken back, while it still can. */
    internal val undo: StateFlow<RowUndo?> = undos.current

    internal val noticeMillis: StateFlow<Long> = noticeMillisOf(playbackPrefs)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 10_000L)

    private val account: StateFlow<ActiveAccount?> =
        authRepository.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<EpisodeUiState> = account
        .flatMapLatest { current ->
            if (current == null) {
                loaded.map { EpisodeUiState(showItemId = itemId, loaded = it) }
            } else {
                combine(
                    libraryRepository.observeItem(current, itemId),
                    episode,
                    progressRepository.observeAll(current),
                    downloadRepository.observeForItem(current, itemId),
                    combine(loaded, message) { isLoaded, note -> isLoaded to note },
                ) { item, row, progress, downloads, (isLoaded, note) ->
                    val own = progress.firstOrNull {
                        it.libraryItemId == itemId && it.episodeId == episodeId
                    }
                    EpisodeUiState(
                        showTitle = item?.title.orEmpty(),
                        showItemId = itemId,
                        episode = row,
                        coverUrl = "${current.baseUrl}/api/items/$itemId/cover?width=400",
                        progressFraction = own?.progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f,
                        positionSec = own?.currentTimeSec ?: 0.0,
                        isFinished = own?.isFinished == true,
                        download = downloads.firstOrNull { it.episodeId == episodeId },
                        loaded = isLoaded,
                        message = note,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EpisodeUiState(showItemId = itemId))

    fun download() {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            downloadRepository.download(current, itemId, episodeId)
                .onFailure { message.value = it.message ?: "Could not start the download" }
        }
    }

    /** Takes the download away at once; the snackbar offers it back. */
    fun removeDownload() {
        val row = state.value.download
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            undos.removeDownload(current, itemId, episodeId, row)
        }
    }

    fun playNext() {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            queueRepository.addNext(current, itemId, episodeId)
            message.value = "Playing next"
        }
    }

    fun addToQueue() {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            queueRepository.addLast(current, itemId, episodeId)
            message.value = "Added to the queue"
        }
    }

    /**
     * The same mark the episode list sets, with the duration as the fallback it needs.
     * Undo in the snackbar puts the position back, so a mistaken mark costs nobody their place.
     */
    fun setFinished(isFinished: Boolean) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            val duration = state.value.episode?.durationSec ?: 0.0
            undos.setFinished(current, itemId, episodeId, isFinished, duration)
        }
    }

    internal fun undo(shown: RowUndo) = undos.undo(shown)

    internal fun dismissUndo(shown: RowUndo) = undos.dismiss(shown)

    /** Starting playback here settles a finished-mark undo for this episode. */
    fun onPlayStarted() = undos.dropProgressUndo(itemId, episodeId)

    override fun onCleared() {
        undos.settleOnClose()
    }

    fun dismissMessage() {
        message.value = null
    }

    private suspend fun read(current: ActiveAccount) {
        episode.value = libraryRepository.episode(current, episodeId)
    }

    init {
        viewModelScope.launch {
            try {
                // Inside the try, so that no account still ends the wait.
                val current = authRepository.account() ?: return@launch
                read(current)
                // The mirror is drawn first and the sync is a top-up, exactly as on the item
                // page. It matters more here: this page can be opened from a notification, and
                // waiting on the network before drawing show notes that are already on the
                // phone would make an offline tap look like a broken page.
                libraryRepository.syncItemDetail(current, itemId)
                read(current)
            } finally {
                // Only now is a missing row really missing. Set in finally so that a sync
                // that throws still ends the wait.
                loaded.value = true
            }
        }
    }
}
