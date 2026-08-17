package io.github.lightheaded.lugu.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.download.DownloadRepository
import io.github.lightheaded.lugu.core.download.DownloadStatus
import io.github.lightheaded.lugu.core.model.ItemSort
import io.github.lightheaded.lugu.core.model.ListControls
import io.github.lightheaded.lugu.core.model.ListFacts
import io.github.lightheaded.lugu.core.model.ListFilter
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.DownloadPrefs
import io.github.lightheaded.lugu.core.sync.DownloadSettings
import io.github.lightheaded.lugu.core.sync.LibraryPrefs
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** An item and an episode together name a download; the item alone does not. */
internal val DownloadStatus.rowKey: String get() = "$libraryItemId#${episodeId.orEmpty()}"

data class DownloadsUiState(
    val downloads: List<DownloadStatus> = emptyList(),
    /** After the search, the filter and the sort — what the list draws. */
    val visible: List<DownloadStatus> = emptyList(),
    val bytesUsed: Long = 0,
    /**
     * Bytes held by audio that was streamed rather than downloaded.
     *
     * Shown apart from [bytesUsed] and never added to it. A download was asked for and is
     * never evicted; retained streamed audio is disposable and drops oldest-first at its
     * own bound. Adding them would put a figure beside the cap that the cap does not
     * govern.
     */
    val retainedStreamBytes: Long = 0,
    val settings: DownloadSettings = DownloadSettings(),
    val query: String = "",
    val sort: ItemSort = ItemSort.ADDED,
    val filter: ListFilter = ListFilter.ALL,
    val selectionActive: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    /** Why something this screen was asked to do did not happen. */
    val message: String? = null,
) {
    val active: List<DownloadStatus> get() = downloads.filter { it.isActive }
    val complete: List<DownloadStatus> get() = downloads.filter { it.isComplete }
    val failed: List<DownloadStatus> get() = downloads.filter { it.isFailed }

    val fractionOfCap: Float
        get() = if (settings.storageCapBytes > 0) {
            (bytesUsed.toDouble() / settings.storageCapBytes).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val downloadRepository: DownloadRepository,
    private val libraryPrefs: LibraryPrefs,
    downloadPrefs: DownloadPrefs,
) : ViewModel() {

    private val query = MutableStateFlow("")

    // Remembered between visits, under this screen's own two keys. Borrowing the grid's
    // would have tied two unrelated screens together — sorting downloads by size would
    // re-order the library — which is why they were held in memory at first, and holding
    // them in memory meant re-picking the ordering on every visit instead.
    //
    // The search box is deliberately *not* remembered. An ordering is a decision about how
    // a list is read; a search is a thing being looked for, and returning to a screen
    // showing three of forty downloads with a stale word in the box reads as lost data.
    //
    // Read as one pair rather than as two flows because `combine` tops out at five, and
    // they arrive together from one store anyway.
    private val listPrefs = libraryPrefs.settings.map { it.downloadSort to it.downloadFilter }

    private val message = MutableStateFlow<String?>(null)

    private val selection = MutableStateFlow(Selection())

    private val account: StateFlow<ActiveAccount?> =
        authRepository.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val content: Flow<DownloadsUiState> = account
        .flatMapLatest { current ->
            if (current == null) {
                flowOf(DownloadsUiState())
            } else {
                combine(
                    downloadRepository.observeAll(current),
                    downloadRepository.observeBytesUsed(current),
                    downloadPrefs.settings,
                ) { downloads, bytes, settings ->
                    DownloadsUiState(downloads = downloads, bytesUsed = bytes, settings = settings)
                }.map { it.copy(retainedStreamBytes = downloadRepository.retainedStreamBytes()) }
            }
        }

    val state: StateFlow<DownloadsUiState> =
        combine(content, query, listPrefs, message, selection) { base, search, prefs, note, picked ->
            val (order, chosenFilter) = prefs
            val facts = base.downloads.factsByKey()
            val visible = ListControls.sortItems(
                base.downloads.filter {
                    val row = facts.getValue(it.rowKey)
                    ListControls.matches(row, chosenFilter) && ListControls.matches(row, search)
                },
                order,
            ) { facts.getValue(it.rowKey) }
            val onScreen = picked.retaining(visible.map { it.rowKey })
            base.copy(
                visible = visible,
                query = search,
                sort = order,
                filter = chosenFilter,
                selectionActive = onScreen.active,
                selectedIds = onScreen.ids,
                message = note,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun setSort(value: ItemSort) {
        viewModelScope.launch { libraryPrefs.setDownloadSort(value) }
    }

    fun setFilter(value: ListFilter) {
        viewModelScope.launch { libraryPrefs.setDownloadFilter(value) }
    }

    fun toggleSelection(key: String) {
        selection.value = selection.value.toggle(key)
    }

    fun selectAllVisible() {
        selection.value = selection.value.selectAll(state.value.visible.map { it.rowKey })
    }

    fun clearSelection() {
        selection.value = selection.value.cleared()
    }

    fun remove(status: DownloadStatus) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            downloadRepository.remove(current, status.libraryItemId, status.episodeId)
        }
    }

    /**
     * Clearing space is the reason anyone opens this screen, and it is rarely one book.
     *
     * No confirmation: the rows say how much each is worth in bytes, the selection had to
     * be made deliberately, and anything deleted can be downloaded again.
     */
    fun removeSelected() {
        val chosen = state.value.visible.filter { it.rowKey in state.value.selectedIds }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            chosen.forEach { downloadRepository.remove(current, it.libraryItemId, it.episodeId) }
            clearSelection()
        }
    }

    /**
     * Starts a failed download again, and says so when it will not start.
     *
     * The refusal was thrown away here, which made the commonest failure on this screen
     * invisible: a download stopped by the storage cap keeps the ordinary retry button, and
     * pressing it hits the same cap and is refused before anything is enqueued. Nothing
     * changed on screen, so the button read as broken rather than as the cap holding. The
     * refusal states its own arithmetic — "Needs 56 MB, and 7.6 GB of the 8 GB cap is
     * already used" — which is the sentence that says what to change first.
     */
    fun retry(status: DownloadStatus) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            downloadRepository.download(current, status.libraryItemId, status.episodeId)
                .onFailure { failure ->
                    message.value = failure.message ?: "That download could not be started again"
                }
        }
    }

    fun dismissMessage() {
        message.value = null
    }

    /**
     * Downloads described in the shared vocabulary.
     *
     * Recency goes in as a rank rather than a timestamp, because a [DownloadStatus] does
     * not carry one — the repository already returns rows newest first, so position in
     * that list is the only ordering information there is. Size is a real field, so it
     * needs no such trick.
     */
    private fun List<DownloadStatus>.factsByKey(): Map<String, ListFacts> =
        withIndex().associate { (index, status) ->
            status.rowKey to ListFacts(
                title = status.title,
                secondary = status.author,
                addedAtMs = (size - index).toLong(),
                sizeBytes = maxOf(status.bytesTotal, status.bytesDownloaded),
                // A finished download is not "in progress", whatever its percentage says.
                progressFraction = if (status.isComplete) 0f else status.percent.coerceIn(0f, 1f),
                isDownloaded = status.isComplete,
            )
        }
}
