package io.github.lightheaded.lugu.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.download.DownloadRepository
import io.github.lightheaded.lugu.core.model.ItemSort
import io.github.lightheaded.lugu.core.model.Library
import io.github.lightheaded.lugu.core.model.LibraryItem
import io.github.lightheaded.lugu.core.model.ListControls
import io.github.lightheaded.lugu.core.model.ListFacts
import io.github.lightheaded.lugu.core.model.ListFilter
import io.github.lightheaded.lugu.core.model.MediaProgress
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.LibraryPrefs
import io.github.lightheaded.lugu.core.sync.LibraryRepository
import io.github.lightheaded.lugu.core.sync.LibrarySettings
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryRow(
    val item: LibraryItem,
    val progress: MediaProgress?,
) {
    val progressFraction: Float
        get() = progress?.progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f
}

data class LibraryUiState(
    val libraries: List<Library> = emptyList(),
    val selectedLibraryId: String? = null,
    val items: List<LibraryRow> = emptyList(),
    val query: String = "",
    val sort: ItemSort = ItemSort.TITLE,
    val filter: ListFilter = ListFilter.ALL,
    val isSyncing: Boolean = false,
    val syncMessage: String? = null,
    val error: String? = null,
)

/**
 * Everything in one library, in whatever order and subset the listener last asked for.
 *
 * The computed shelves moved to [HomeViewModel]: they answer a different question, and
 * having both here is what allowed the grid to be scoped to the selected library while
 * the shelves above it were not. This one is only ever about the selected library, which
 * makes the picker mean exactly one thing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val libraryRepository: LibraryRepository,
    private val progressRepository: ProgressRepository,
    private val downloadRepository: DownloadRepository,
    private val libraryPrefs: LibraryPrefs,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val syncing = MutableStateFlow(false)
    private val syncMessage = MutableStateFlow<String?>(null)
    private val error = MutableStateFlow<String?>(null)

    private val account: StateFlow<ActiveAccount?> =
        authRepository.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Hidden media types are already filtered out here, so the picker cannot offer one. */
    private val libraries: StateFlow<List<Library>> = account
        .flatMapLatest { current ->
            if (current == null) flowOf(emptyList()) else libraryRepository.observeLibraries(current)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val settings: StateFlow<LibrarySettings> = libraryPrefs.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibrarySettings())

    /**
     * Stored rather than held here, because the shelves on the other tab are scoped by the
     * same value. One screen owning it and telling the other leaves a frame where the two
     * disagree, which shows up as shelves that span every library and then snap.
     */
    private val selectedLibraryId: StateFlow<String?> = settings
        .map { it.selectedLibraryId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val progressByKey: StateFlow<Map<String, MediaProgress>> = account
        .flatMapLatest { current ->
            if (current == null) flowOf(emptyList()) else progressRepository.observeAll(current)
        }
        .map { list -> list.associateBy { "${it.libraryItemId}#${it.episodeId.orEmpty()}" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Which items are on the phone, so the "Downloaded" filter answers with the truth
     * rather than with nothing. Only a finished download counts: an in-flight one is not
     * yet something that plays without a network.
     */
    private val downloadedItemIds: StateFlow<Set<String>> = account
        .flatMapLatest { current ->
            if (current == null) flowOf(emptyList()) else downloadRepository.observeAll(current)
        }
        .map { rows -> rows.filter { it.isComplete }.map { it.libraryItemId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val items: StateFlow<List<LibraryItem>> =
        combine(account, selectedLibraryId, query) { current, libraryId, text ->
            Triple(current, libraryId, text)
        }.flatMapLatest { (current, libraryId, text) ->
            when {
                current == null || libraryId == null -> flowOf(emptyList())
                // Search stays on the full-text index: it matches metadata the grid does
                // not render, which the in-memory substring pass could not.
                text.isBlank() -> libraryRepository.observeItems(current, libraryId)
                else -> libraryRepository.search(current, libraryId, text)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<LibraryUiState> = combine(
        libraries,
        selectedLibraryId,
        items,
        combine(progressByKey, downloadedItemIds, settings) { progress, downloaded, prefs ->
            Shaping(progress, downloaded, prefs.itemSort, prefs.itemFilter)
        },
        combine(query, syncing, syncMessage, error) { text, isSyncing, message, err ->
            Extras(text, isSyncing, message, err)
        },
    ) { libs, selected, itemList, shaping, extras ->
        val rows = itemList.map { LibraryRow(it, shaping.progress["${it.id}#"]) }
        // Facts are built once per row rather than inside the comparator, which would
        // rebuild them O(n log n) times on every emission of a large library.
        val facts = rows.associate { it.item.id to it.facts(it.item.id in shaping.downloaded) }

        LibraryUiState(
            libraries = libs,
            selectedLibraryId = selected,
            items = ListControls.sortItems(
                rows.filter { ListControls.matches(facts.getValue(it.item.id), shaping.filter) },
                shaping.sort,
            ) { facts.getValue(it.item.id) },
            query = extras.query,
            sort = shaping.sort,
            filter = shaping.filter,
            isSyncing = extras.isSyncing,
            syncMessage = extras.message,
            error = extras.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    private data class Shaping(
        val progress: Map<String, MediaProgress>,
        val downloaded: Set<String>,
        val sort: ItemSort,
        val filter: ListFilter,
    )

    private data class Extras(
        val query: String,
        val isSyncing: Boolean,
        val message: String?,
        val error: String?,
    )

    init {
        // The selection has to stay inside the set the picker can show. Hiding a media
        // type removes its library from that set, and a selection left pointing at it
        // would scope the grid to something with no chip to change it back.
        viewModelScope.launch {
            libraries.collect { available ->
                if (available.isEmpty()) return@collect
                val current = selectedLibraryId.value
                if (current != null && available.any { it.id == current }) return@collect
                val wanted = account.value?.defaultLibraryId
                    ?.takeIf { id -> available.any { it.id == id } }
                    ?: available.first().id
                libraryPrefs.setSelectedLibraryId(wanted)
            }
        }
        refresh()
    }

    fun selectLibrary(libraryId: String) {
        viewModelScope.launch {
            libraryPrefs.setSelectedLibraryId(libraryId)
            refresh()
        }
    }

    fun onQueryChange(value: String) = query.update { value }

    /** Remembered rather than reset per visit: an ordering someone chose is a decision. */
    fun setSort(sort: ItemSort) {
        viewModelScope.launch { libraryPrefs.setItemSort(sort) }
    }

    fun setFilter(filter: ListFilter) {
        viewModelScope.launch { libraryPrefs.setItemFilter(filter) }
    }

    /**
     * Re-mirrors from the server. The UI never waits on this: everything on screen is
     * already rendering from Room, so a failure here is a message, not an empty screen.
     */
    fun refresh() {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            syncing.value = true
            error.value = null

            libraryRepository.syncLibraries(current)
                .onFailure { error.value = it.message ?: "Could not reach the server" }

            // Sync the library in view first; the rest catch up on the periodic sweep.
            val targets = listOfNotNull(selectedLibraryId.value)
            targets.forEach { id ->
                libraryRepository.syncLibraryItems(current, id) { synced, total ->
                    syncMessage.value = "Syncing $synced of $total"
                }.onFailure { error.value = it.message ?: "Could not sync the library" }
            }

            progressRepository.reconcile(current)
            syncMessage.value = null
            syncing.value = false
        }
    }

    /**
     * Cover URLs are plain server URLs; the shared OkHttp interceptor attaches auth,
     * so Coil needs nothing special beyond the address.
     */
    fun coverUrl(itemId: String, width: Int = 400): String? =
        account.value?.let { "${it.baseUrl}/api/items/$itemId/cover?width=$width" }
}

/**
 * What sorting and filtering are allowed to know about a row.
 *
 * The author is the secondary field because it is what the grid shows underneath the
 * title, so ordering by it lands where the eye already is.
 */
private fun LibraryRow.facts(isDownloaded: Boolean): ListFacts = ListFacts(
    title = item.title,
    secondary = item.authorName,
    addedAtMs = item.addedAtMs,
    durationSec = item.durationSec,
    progressFraction = progressFraction,
    isFinished = progress?.isFinished == true,
    isDownloaded = isDownloaded,
)
