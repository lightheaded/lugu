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
import io.github.lightheaded.lugu.core.sync.QueueRepository
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryRow(
    val item: LibraryItem,
    val progress: MediaProgress?,
) {
    val progressFraction: Float
        get() = progress?.progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f

    /**
     * Whether this row's progress is the item's own or borrowed from one of its episodes.
     *
     * A podcast has no progress row at the item level, so a card for one shows the position
     * of the episode that would be resumed. That is the right thing to *draw* and the wrong
     * thing to draw conclusions from: one finished episode does not finish a feed.
     */
    val progressIsEpisode: Boolean get() = progress?.episodeId != null

    /** A feed is never finished; only a thing with its own progress row can be. */
    val isFinished: Boolean get() = progress?.isFinished == true && !progressIsEpisode

    /**
     * What the bar under a cover means, said out loud.
     *
     * A podcast's says which episode it is about, because "62% listened" against a show
     * with four hundred episodes would be read as a claim about the show.
     */
    val progressDescription: String
        get() {
            val percent = (progressFraction * 100).toInt()
            return if (progressIsEpisode) {
                "Latest episode $percent% listened"
            } else {
                "$percent% listened"
            }
        }
}

/**
 * What a sync is doing, and how far through it is.
 *
 * A sentence rather than a spinner because the thing on screen used to say only that
 * *something* was happening, which is the least useful true statement an app can make. The
 * fraction is `null` until the server has said how many items there are — an indeterminate
 * bar for a second, then a real one, rather than a bar that pretends to know from the
 * start.
 */
data class SyncNote(val text: String, val fraction: Float? = null)

data class LibraryUiState(
    val libraries: List<Library> = emptyList(),
    val selectedLibraryId: String? = null,
    val items: List<LibraryRow> = emptyList(),
    val query: String = "",
    val sort: ItemSort = ItemSort.TITLE,
    val filter: ListFilter = ListFilter.ALL,
    val isSyncing: Boolean = false,
    /**
     * True only for a sync somebody asked for by pulling the grid down.
     *
     * The pull indicator means "your pull is being honoured", so it may not appear for the
     * sync that runs on its own when the app opens — a spinner answering a gesture nobody
     * made is the same lie the top bar's used to tell.
     */
    val isPulling: Boolean = false,
    val syncNote: SyncNote? = null,
    val error: String? = null,
    val selectionActive: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    /** What a batch action just did, since none of them change the grid visibly. */
    val message: String? = null,
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
    private val queueRepository: QueueRepository,
    private val libraryPrefs: LibraryPrefs,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val syncing = MutableStateFlow(false)
    private val pulling = MutableStateFlow(false)
    private val syncNote = MutableStateFlow<SyncNote?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null)

    // The same reducer the episode list uses. Selection is a property of a list rather than
    // of a screen, and a second implementation here is how two lists end up disagreeing
    // about what select-all means.
    private val selection = MutableStateFlow(Selection())

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

    /**
     * Progress per item, falling back to the item's most recent episode where it has no
     * row of its own.
     *
     * The grid used to read the item-level key alone, which a podcast never has — so every
     * podcast cover showed no progress at all, however much of it had been listened to,
     * and the "In progress" filter could not see one either. The browse and collection
     * grids already read it this way; this one was the odd screen out.
     */
    private val progressByItem: StateFlow<Map<String, MediaProgress>> = account
        .flatMapLatest { current ->
            if (current == null) flowOf(emptyList()) else progressRepository.observeAll(current)
        }
        .map { list -> ItemProgress.byItem(list) }
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

    /**
     * Everything the grid shows that is not the grid.
     *
     * Nested one level deeper than the rest because `combine` tops out at five typed flows
     * and there are seven of these. The nesting expresses that limit, not a grouping worth
     * reading anything into.
     */
    private val extras: Flow<Extras> = combine(
        combine(query, syncing, pulling, syncNote, error) { text, isSyncing, pulled, note, err ->
            Extras(text, isSyncing, pulled, note, err)
        },
        message,
        selection,
    ) { base, note, picked -> base.copy(note = note, selection = picked) }

    val state: StateFlow<LibraryUiState> = combine(
        libraries,
        selectedLibraryId,
        items,
        combine(progressByItem, downloadedItemIds, settings) { progress, downloaded, prefs ->
            Shaping(progress, downloaded, prefs.itemSort, prefs.itemFilter)
        },
        extras,
    ) { libs, selected, itemList, shaping, extras ->
        val rows = itemList.map { LibraryRow(it, shaping.progress[it.id]) }
        // Facts are built once per row rather than inside the comparator, which would
        // rebuild them O(n log n) times on every emission of a large library.
        val facts = rows.associate { it.item.id to it.facts(it.item.id in shaping.downloaded) }
        val visible = ListControls.sortItems(
            rows.filter { ListControls.matches(facts.getValue(it.item.id), shaping.filter) },
            shaping.sort,
        ) { facts.getValue(it.item.id) }
        val onScreen = extras.selection.retaining(visible.map { it.item.id })

        LibraryUiState(
            libraries = libs,
            selectedLibraryId = selected,
            items = visible,
            query = extras.query,
            sort = shaping.sort,
            filter = shaping.filter,
            isSyncing = extras.isSyncing,
            isPulling = extras.isPulling,
            syncNote = extras.syncNote,
            error = extras.error,
            selectionActive = onScreen.active,
            selectedIds = onScreen.ids,
            message = extras.note,
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
        val isPulling: Boolean,
        val syncNote: SyncNote?,
        val error: String?,
        val note: String? = null,
        val selection: Selection = Selection(),
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

    fun toggleSelection(itemId: String) {
        selection.value = selection.value.toggle(itemId)
    }

    fun selectAllVisible() {
        selection.value = selection.value.selectAll(state.value.items.map { it.item.id })
    }

    fun clearSelection() {
        selection.value = selection.value.cleared()
    }

    fun dismissMessage() {
        message.value = null
    }

    /**
     * The point of picking eight books is not pressing download eight times.
     *
     * Only the first refusal is reported. The storage cap is the usual reason a batch
     * stops, and once it is reached every remaining book gives the same answer — eight
     * identical messages say nothing the first one did not.
     */
    fun downloadSelected() {
        val chosen = selectedRows()
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            var refusal: String? = null
            chosen.forEach { row ->
                downloadRepository.download(current, row.item.id, null)
                    .onFailure { failure -> refusal = refusal ?: failure.message }
            }
            message.value = refusal ?: "Downloading ${countOf(chosen.size)}"
            clearSelection()
        }
    }

    fun addSelectedToQueue() {
        val chosen = selectedRows()
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            chosen.forEach { queueRepository.addLast(current, it.item.id, null) }
            message.value = "Added ${countOf(chosen.size)} to the queue"
            clearSelection()
        }
    }

    /**
     * Marks the selection finished, or takes the mark off again.
     *
     * Un-finishing resets the position to the start rather than leaving it at the end,
     * which is what the server's own web client does: "not finished" there means the book
     * is waiting to be listened to, not parked one second from the last page.
     *
     * The item's own duration is the fallback because a book nobody has opened has no
     * progress row to read one from, and a finished position of zero seconds would sync a
     * completion the server then computes as nought per cent.
     */
    fun setSelectedFinished(isFinished: Boolean) {
        val chosen = selectedRows()
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            chosen.forEach { row ->
                progressRepository.setFinished(
                    account = current,
                    itemId = row.item.id,
                    episodeId = null,
                    isFinished = isFinished,
                    fallbackDurationSec = row.item.durationSec,
                )
            }
            val verb = if (isFinished) "Marked" else "Unmarked"
            message.value = "$verb ${countOf(chosen.size)}"
            clearSelection()
        }
    }

    /** Read from the visible list, so a filtered-out row can never be acted on unseen. */
    private fun selectedRows(): List<LibraryRow> =
        state.value.items.filter { it.item.id in state.value.selectedIds }

    private fun countOf(size: Int): String = if (size == 1) "1 item" else "$size items"

    /**
     * Re-mirrors from the server. The UI never waits on this: everything on screen is
     * already rendering from Room, so a failure here is a message, not an empty screen.
     */
    fun refresh() = sync(pulled = false)

    /**
     * The same work, asked for by pulling the grid down.
     *
     * Told apart from the automatic pass only so the pull indicator answers the gesture and
     * nothing else — see [LibraryUiState.isPulling].
     */
    fun pullToRefresh() = sync(pulled = true)

    /** Clears whatever the status line is currently saying. */
    fun dismissStatus() {
        message.value = null
        error.value = null
    }

    private fun sync(pulled: Boolean) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            syncing.value = true
            pulling.value = pulled
            error.value = null
            // Named from the start, so the line never appears saying nothing while the
            // first request is in flight.
            syncNote.value = SyncNote("Checking the server")

            val libraries = libraryRepository.syncLibraries(current)
                .onFailure { error.value = it.message ?: "Could not reach the server" }
                .getOrDefault(emptyList())

            // Sync the library in view first; the rest catch up on the periodic sweep.
            //
            // The fallback is what makes a fresh sign-in work at all. There is no selection
            // yet the first time this runs: the picker's default is chosen by the collector
            // above, which cannot have run before this line, because the libraries it waits
            // on are fetched on the line above it. With `listOfNotNull(selectedLibraryId)`
            // alone the list was empty, so the very first sync after signing in mirrored the
            // libraries and **none of their items** — measured on a device: `library` held 2
            // rows and `library_item` held 0, and it stayed 0 through opening the Library
            // tab, because nothing calls this again. Only restarting the app fixed it, by
            // which point the selection had been persisted.
            //
            // Which meant a new account's first sight of lugu was an empty grid, and
            // anything reading Room in the meantime — Home's shelves, the car's browse tree,
            // "play X on lugu" — found nothing there either.
            val targets = listOfNotNull(
                selectedLibraryId.value
                    ?: current.defaultLibraryId?.takeIf { id -> libraries.any { it.id == id } }
                    ?: libraries.firstOrNull()?.id,
            )
            targets.forEach { id ->
                // Named where the name is known. "Syncing Audiobooks" answers the question
                // somebody watching the line is actually asking, which is what is being
                // fetched — and on a server with several libraries it also says which.
                val name = libraries.firstOrNull { it.id == id }?.name
                val what = if (name.isNullOrBlank()) "your library" else name
                syncNote.value = SyncNote("Syncing $what")
                libraryRepository.syncLibraryItems(current, id) { synced, total ->
                    syncNote.value = SyncNote(
                        text = "Syncing $what — $synced of $total",
                        // A total of zero is a library with nothing in it, not a job that is
                        // finished, and dividing by it would say so in the worst way.
                        fraction = if (total > 0) synced.toFloat() / total else null,
                    )
                }.onFailure { error.value = it.message ?: "Could not sync the library" }
            }

            syncNote.value = SyncNote("Syncing where you got to")
            progressRepository.reconcile(current)
            syncNote.value = null
            syncing.value = false
            pulling.value = false
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
    isFinished = isFinished,
    isDownloaded = isDownloaded,
)
