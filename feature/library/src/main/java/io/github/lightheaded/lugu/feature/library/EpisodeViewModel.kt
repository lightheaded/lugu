package io.github.lightheaded.lugu.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.download.DownloadRepository
import io.github.lightheaded.lugu.core.download.DownloadStatus
import io.github.lightheaded.lugu.core.model.PodcastEpisode
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.LibraryRepository
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import io.github.lightheaded.lugu.core.sync.QueueRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EpisodeUiState(
    val showTitle: String = "",
    val episode: PodcastEpisode? = null,
    val progressFraction: Float = 0f,
    val positionSec: Double = 0.0,
    val isFinished: Boolean = false,
    val download: DownloadStatus? = null,
    /** False only until the first read, so a missing episode does not flash on the way in. */
    val loaded: Boolean = false,
    val message: String? = null,
)

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
) : ViewModel() {

    private val itemId: String = checkNotNull(savedStateHandle["itemId"])

    private val episodeId: String = checkNotNull(savedStateHandle["episodeId"])

    private val episode = MutableStateFlow<PodcastEpisode?>(null)

    private val loaded = MutableStateFlow(false)

    private val message = MutableStateFlow<String?>(null)

    private val account: StateFlow<ActiveAccount?> =
        authRepository.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<EpisodeUiState> = account
        .flatMapLatest { current ->
            if (current == null) {
                flowOf(EpisodeUiState())
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
                        episode = row,
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EpisodeUiState())

    fun download() {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            downloadRepository.download(current, itemId, episodeId)
                .onFailure { message.value = it.message ?: "Could not start the download" }
        }
    }

    fun removeDownload() {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            downloadRepository.remove(current, itemId, episodeId)
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

    /** The same mark the episode list sets, with the duration as the fallback it needs. */
    fun setFinished(isFinished: Boolean) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            val duration = state.value.episode?.durationSec ?: 0.0
            progressRepository.setFinished(current, itemId, episodeId, isFinished, duration)
            message.value = if (isFinished) "Marked as finished" else "Marked as not finished"
        }
    }

    fun dismissMessage() {
        message.value = null
    }

    private suspend fun read(current: ActiveAccount) {
        episode.value = libraryRepository.episode(current, episodeId)
        loaded.value = true
    }

    init {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            read(current)
            // The mirror is drawn first and the sync is a top-up, exactly as on the item
            // page. It matters more here: this page can be opened from a notification, and
            // waiting on the network before drawing show notes that are already on the
            // phone would make an offline tap look like a broken page.
            libraryRepository.syncItemDetail(current, itemId)
            read(current)
        }
    }
}
