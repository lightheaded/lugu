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
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.CollectionRepository
import io.github.lightheaded.lugu.core.sync.LibraryPrefs
import io.github.lightheaded.lugu.core.sync.LibraryRepository
import io.github.lightheaded.lugu.core.sync.LibrarySettings
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import io.github.lightheaded.lugu.core.sync.QueueRepository
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryRow(
    val item: LibraryItem,
    val progress: MediaProgress?,
    /** A finished download of the item is on the phone, so it plays without a network. */
    val isDownloaded: Boolean = false,
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
            // A finished book reads as finished, not as "97% listened".
            if (isFinished) return "Finished"
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
    /** Whether the selected library holds podcasts, which decides the sorts on offer. */
    val isPodcastLibrary: Boolean = false,
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
    /**
     * The collections of the library in view, for the selection bar's picker.
     *
     * Scoped to that library because the server will not put a book into a collection of
     * another one. The `contains` mark is always false here: it answers a question about
     * one item, and a selection is many.
     */
    val collections: List<CollectionChoice> = emptyList(),
    /**
     * False until Room has answered for the library in view.
     *
     * The state before that is empty because nothing is known yet, not because the library
     * is. The grid draws no empty state until this is true, so the first frame does not say
     * "Nothing in this library yet" about a library that has books in it.
     */
    val loaded: Boolean = false,
)

/**
 * Everything in one library, in whatever order and subset the listener last asked for.
 *
 * The computed shelves moved to [HomeViewModel]: they answer a different question, and
 * having both here is what allowed the grid to be scoped to the selected library while
 * the shelves above it were not. This one is only ever about the selected library, which
 * makes the picker mean exactly one thing.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val libraryRepository: LibraryRepository,
    private val progressRepository: ProgressRepository,
    private val downloadRepository: DownloadRepository,
    private val queueRepository: QueueRepository,
    private val libraryPrefs: LibraryPrefs,
    private val collectionRepository: CollectionRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val syncing = MutableStateFlow(false)
    private val pulling = MutableStateFlow(false)
    private val syncNote = MutableStateFlow<SyncNote?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null)

    /**
     * True once a library sync has ended, with or without an answer.
     *
     * Until then an account with no library may only be one whose libraries are not
     * mirrored yet, so the grid stays unloaded rather than saying it is empty.
     */
    private val librariesChecked = MutableStateFlow(false)

    /** The search box's text, read by the box itself and never through [state]. */
    val queryText: StateFlow<String> = query

    // Declared before `init`, which starts the first sync: a property declared below it
    // would be reset to null after that sync had stored itself here.
    /** The newest sync. Only it may say that syncing has ended. */
    private var syncJob: Job? = null

    // The same reducer the episode list uses. Selection is a property of a list rather than
    // of a screen, and a second implementation here is how two lists end up disagreeing
    // about what select-all means.
    private val selection = MutableStateFlow(Selection())

    // The shared flows below hold no made-up first value. The grid's state is built from
    // them, so that its first emission is what Room and the preferences really hold, and
    // `loaded` can mean that. The state flows beside them keep a default for everything else.
    private val accountUpdates = authRepository.observeAccount()
        .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    private val account: StateFlow<ActiveAccount?> =
        accountUpdates.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Hidden media types are already filtered out here, so the picker cannot offer one. */
    private val libraryUpdates = accountUpdates
        .flatMapLatest { current ->
            if (current == null) flowOf(emptyList()) else libraryRepository.observeLibraries(current)
        }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    // Eagerly, for the same reason as [selectedLibraryId]: `setSort` reads `.value`.
    private val libraries: StateFlow<List<Library>> =
        libraryUpdates.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val settingsUpdates = libraryPrefs.settings
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val settings: StateFlow<LibrarySettings> =
        settingsUpdates.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibrarySettings())

    /**
     * Stored rather than held here, because the shelves on the other tab are scoped by the
     * same value. One screen owning it and telling the other leaves a frame where the two
     * disagree, which shows up as shelves that span every library and then snap.
     */
    // Eagerly, because actions read `.value`: shared only while collected, it stayed at
    // null when nothing collected it, and a null here reads as "no library chosen yet".
    private val selectedLibraryId: StateFlow<String?> = settingsUpdates
        .map { it.selectedLibraryId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Progress per item, falling back to the item's most recent episode where it has no
     * row of its own.
     *
     * The grid used to read the item-level key alone, which a podcast never has — so every
     * podcast cover showed no progress at all, however much of it had been listened to,
     * and the "In progress" filter could not see one either. The browse and collection
     * grids already read it this way; this one was the odd screen out.
     *
     * The last-played times come from the same rows in the same pass, so that the
     * recently-played sort does not open a second query on the progress table.
     */
    private val progressByItem: StateFlow<ProgressByItem> = account
        .flatMapLatest { current ->
            if (current == null) flowOf(emptyList()) else progressRepository.observeAll(current)
        }
        .map { list -> ProgressByItem(ItemProgress.byItem(list), ItemProgress.lastPlayedByItem(list)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressByItem())

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

    /** When each podcast's newest episode came out, for the latest-episode sort. */
    private val latestEpisodeDates: StateFlow<Map<String, Long>> = account
        .flatMapLatest { current ->
            if (current == null) flowOf(emptyMap()) else libraryRepository.observeLatestEpisodeDates(current)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Read from Room only. Opening the picker is what asks the server, through
     * [refreshCollections], because the listing is the heaviest request the app makes.
     */
    private val collections: StateFlow<List<CollectionChoice>> =
        combine(account, selectedLibraryId) { current, libraryId -> current to libraryId }
            .flatMapLatest { (current, libraryId) ->
                if (current == null || libraryId == null) {
                    flowOf(emptyList())
                } else {
                    collectionRepository.observeCollections(current, libraryId)
                        .map { collectionChoices(it, membership = emptySet()) }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * What the search runs on: the box's text after a short pause in typing, so that each
     * key does not re-query and re-sort the library. The box itself reads [queryText] and shows
     * each key at once. Clearing is not delayed, because the full grid is already known.
     */
    private val searchQuery: Flow<String> =
        query.debounce { text -> if (text.isBlank()) 0L else TYPING_PAUSE_MS }

    /** `null` while there is no answer yet: no library is selected so far. */
    private val items: Flow<List<LibraryItem>?> =
        combine(
            accountUpdates,
            settingsUpdates.map { it.selectedLibraryId }.distinctUntilChanged(),
            searchQuery,
        ) { current, libraryId, text ->
            Triple(current, libraryId, text)
        }.flatMapLatest { (current, libraryId, text) ->
            when {
                current == null -> flowOf<List<LibraryItem>?>(emptyList())
                // The picker chooses a default once the libraries are mirrored.
                libraryId == null -> flowOf<List<LibraryItem>?>(null)
                // Search stays on the full-text index: it matches metadata the grid does
                // not render, which the in-memory substring pass could not.
                text.isBlank() -> libraryRepository.observeItems(current, libraryId)
                else -> libraryRepository.search(current, libraryId, text)
            }
        }

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
        collections,
        librariesChecked,
    ) { base, note, picked, choices, checked ->
        base.copy(note = note, selection = picked, collections = choices, librariesChecked = checked)
    }

    /**
     * The grid's state, filtered and sorted.
     *
     * Built from the real first values of the library list, the items and the preferences,
     * so the first emission is already true and sets [LibraryUiState.loaded]. It runs off
     * the main thread, because it filters and sorts the whole library again each time a
     * playback position is written.
     */
    val state: StateFlow<LibraryUiState> = combine(
        libraryUpdates,
        items,
        combine(
            progressByItem,
            downloadedItemIds,
            latestEpisodeDates,
            settingsUpdates,
        ) { progress, downloaded, latest, prefs ->
            Shaping(
                progress = progress,
                downloaded = downloaded,
                latestEpisodes = latest,
                selectedLibraryId = prefs.selectedLibraryId,
                bookSort = prefs.itemSort,
                podcastSort = prefs.podcastSort,
                filter = prefs.itemFilter,
            )
        },
        extras,
    ) { libs, itemList, shaping, extras ->
        val selected = shaping.selectedLibraryId
        val isPodcastLibrary = libs.firstOrNull { it.id == selected }?.mediaType == MediaType.PODCAST
        // Book libraries and podcast libraries each keep their own choice. The book key can
        // still hold "Latest episode" from the time when one key served both, and a book
        // library has no episodes to sort by.
        val stored = if (isPodcastLibrary) shaping.podcastSort else shaping.bookSort
        val sort = if (stored == ItemSort.LATEST_EPISODE && !isPodcastLibrary) ItemSort.TITLE else stored
        // No library selected yet. That is "unknown" until a library sync has ended with
        // nothing to pick, and only then an empty grid.
        val known = itemList != null || (libs.isEmpty() && extras.librariesChecked)
        val rows = itemList.orEmpty().map {
            LibraryRow(it, shaping.progress.shown[it.id], isDownloaded = it.id in shaping.downloaded)
        }
        // Facts are built once per row rather than inside the comparator, which would
        // rebuild them O(n log n) times on every emission of a large library.
        val facts = rows.associate {
            it.item.id to it.facts(
                isDownloaded = it.isDownloaded,
                latestEpisodeAtMs = shaping.latestEpisodes[it.item.id] ?: 0L,
                lastPlayedAtMs = shaping.progress.lastPlayed[it.item.id] ?: 0L,
            )
        }
        val visible = ListControls.sortItems(
            rows.filter { ListControls.matches(facts.getValue(it.item.id), shaping.filter) },
            sort,
        ) { facts.getValue(it.item.id) }
        val onScreen = extras.selection.retaining(visible.map { it.item.id })

        LibraryUiState(
            libraries = libs,
            selectedLibraryId = selected,
            items = visible,
            query = extras.query,
            sort = sort,
            filter = shaping.filter,
            isPodcastLibrary = isPodcastLibrary,
            isSyncing = extras.isSyncing,
            isPulling = extras.isPulling,
            syncNote = extras.syncNote,
            error = extras.error,
            selectionActive = onScreen.active,
            selectedIds = onScreen.ids,
            message = extras.note,
            collections = extras.collections,
            loaded = known,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    private data class ProgressByItem(
        /** The one row each card shows; see [ItemProgress.byItem]. */
        val shown: Map<String, MediaProgress> = emptyMap(),
        val lastPlayed: Map<String, Long> = emptyMap(),
    )

    private data class Shaping(
        val progress: ProgressByItem,
        val downloaded: Set<String>,
        val latestEpisodes: Map<String, Long>,
        val selectedLibraryId: String?,
        val bookSort: ItemSort,
        val podcastSort: ItemSort,
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
        val collections: List<CollectionChoice> = emptyList(),
        val librariesChecked: Boolean = false,
    )

    init {
        // The selection has to stay inside the set the picker can show. Hiding a media
        // type removes its library from that set, and a selection left pointing at it
        // would scope the grid to something with no chip to change it back.
        //
        // Built from the flows with no made-up first value. Read through `.value`, the
        // stored selection was still null at a cold start, so this replaced the listener's
        // library with the default one each time the app opened.
        viewModelScope.launch {
            combine(
                libraryUpdates,
                settingsUpdates.map { it.selectedLibraryId }.distinctUntilChanged(),
                accountUpdates,
            ) { available, current, signedIn -> Triple(available, current, signedIn) }
                .collect { (available, current, signedIn) ->
                    if (available.isEmpty()) return@collect
                    if (current != null && available.any { it.id == current }) return@collect
                    val wanted = signedIn?.defaultLibraryId
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

    /**
     * Remembered rather than reset per visit: an ordering someone chose is a decision.
     *
     * Stored for the kind of library in view, so that a sort chosen among the books does
     * not re-order the podcasts, and the reverse.
     */
    fun setSort(sort: ItemSort) {
        viewModelScope.launch {
            val library = libraries.value.firstOrNull { it.id == selectedLibraryId.value }
            libraryPrefs.setItemSortFor(library?.mediaType, sort)
            if (sort != ItemSort.LATEST_EPISODE || library == null) return@launch
            val current = authRepository.account() ?: return@launch
            fillEpisodeDates(current, library)
        }
    }

    /**
     * Fetches what the latest-episode sort needs, for one podcast library.
     *
     * Only when that sort is chosen: the pass costs a request per podcast the first time,
     * and somebody who sorts by title gets nothing for that data.
     */
    private suspend fun fillEpisodeDates(current: ActiveAccount, library: Library) {
        if (library.mediaType != MediaType.PODCAST) return
        val what = library.name.ifBlank { "your podcasts" }
        var shown = false
        libraryRepository.fillEpisodeDates(current, library.id) { done, total ->
            shown = true
            syncNote.value = SyncNote(
                text = "Checking episode dates in $what — $done of $total",
                fraction = done.toFloat() / total,
            )
        }.onFailure { reportUnlessCancelled(it, "Could not check the episode dates") }
        if (shown) syncNote.value = null
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

    /**
     * Re-mirrors the collections behind the picker.
     *
     * Called when the picker opens, as on the item page, and rate-limited by the repository
     * to one pass in five minutes. A failure says nothing: the picker still renders from
     * Room, and an add that the server refuses reports its own reason.
     */
    fun refreshCollections() {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            collectionRepository.sync(current)
        }
    }

    /**
     * Puts the selected books into one collection, in one request.
     *
     * Nothing is shown before the server answers, for the reason the item page gives: a
     * collection is shared, and a change that appeared here and then failed would be a lie
     * about a list other people read. Offline, the repository refuses at once with a reason,
     * and nothing is queued.
     *
     * The selection stays when the add fails, so the same books can be sent again once the
     * server is back. It clears on success, like every other action on the bar.
     */
    fun addSelectedToCollection(collectionId: String) {
        val plan = collectionAddPlan(selectedRows())
        if (plan.bookIds.isEmpty()) {
            if (plan.skippedPodcasts > 0) message.value = COLLECTIONS_HOLD_BOOKS
            return
        }
        val name = state.value.collections.firstOrNull { it.id == collectionId }?.name
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            collectionRepository.addAll(current, collectionId, plan.bookIds).fold(
                onSuccess = { held ->
                    message.value = collectionBatchLine(name, plan, held)
                    clearSelection()
                },
                onFailure = { message.value = it.message ?: "Could not change the collection" },
            )
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

    /**
     * Starts a sync and cancels the one before it.
     *
     * Each library chip tap starts one, and two at once used to race: the first to end
     * cleared the indicator while the other still ran.
     */
    private fun sync(pulled: Boolean) {
        val previous = syncJob
        // A pull stays answered when a chip tap replaces the sync it started.
        val keepPulling = previous?.isActive == true && pulling.value
        previous?.cancel()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                runSync(pulled = pulled || keepPulling)
            } finally {
                if (syncJob === currentCoroutineContext()[Job]) {
                    syncNote.value = null
                    syncing.value = false
                    pulling.value = false
                }
            }
        }
        syncJob = job
        job.start()
    }

    private suspend fun runSync(pulled: Boolean) {
        val current = authRepository.account() ?: return
        syncing.value = true
        pulling.value = pulled
        error.value = null
        // Named from the start, so the line never appears saying nothing while the
        // first request is in flight.
        syncNote.value = SyncNote("Checking the server")

        val libraries = libraryRepository.syncLibraries(current)
            .onFailure { reportUnlessCancelled(it, "Could not reach the server") }
            .getOrDefault(emptyList())
        currentCoroutineContext().ensureActive()
        librariesChecked.value = true

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
            // The stored value, waited for: at a cold start `.value` is not there yet.
            settingsUpdates.first().selectedLibraryId
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
            // The library in view is one the user has opened, which is the only kind
            // that may later say a new book arrived in it.
            runCatching { libraryRepository.markOpened(current, id) }
            libraryRepository.syncLibraryItems(current, id) { synced, total ->
                syncNote.value = SyncNote(
                    text = "Syncing $what — $synced of $total",
                    // A total of zero is a library with nothing in it, not a job that is
                    // finished, and dividing by it would say so in the worst way.
                    fraction = if (total > 0) synced.toFloat() / total else null,
                )
            }.onFailure { reportUnlessCancelled(it, "Could not sync the library") }
            currentCoroutineContext().ensureActive()

            val library = libraries.firstOrNull { it.id == id }
            if (library != null && libraryPrefs.current().podcastSort == ItemSort.LATEST_EPISODE) {
                fillEpisodeDates(current, library)
            }
        }

        syncNote.value = SyncNote("Syncing where you got to")
        progressRepository.reconcile(current)
    }

    /**
     * The repository wraps its work in `runCatching`, which also catches the cancellation
     * of a sync that a newer one replaced. That is not a failure to show.
     */
    private fun reportUnlessCancelled(failure: Throwable, fallback: String) {
        if (failure is CancellationException) return
        error.value = failure.message ?: fallback
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
private fun LibraryRow.facts(
    isDownloaded: Boolean,
    latestEpisodeAtMs: Long,
    lastPlayedAtMs: Long,
): ListFacts = ListFacts(
    title = item.title,
    secondary = item.authorName,
    addedAtMs = item.addedAtMs,
    publishedAtMs = latestEpisodeAtMs,
    durationSec = item.durationSec,
    progressFraction = progressFraction,
    isFinished = isFinished,
    isDownloaded = isDownloaded,
    lastPlayedAtMs = lastPlayedAtMs,
)

/**
 * Which of the selected rows can go into a collection.
 *
 * A collection holds books only: the server refuses any other kind of item, and its batch
 * route drops one without a word. So the podcasts are taken out here and counted, and the
 * line that reports the result names them. In practice the grid shows one library at a
 * time, and a podcast library has no collections to pick, so the bar disables the action
 * there and this split finds nothing to skip. It is still made, because a skipped podcast
 * that nobody mentions reads as a failed add.
 */
internal data class CollectionAddPlan(val bookIds: List<String>, val skippedPodcasts: Int)

internal fun collectionAddPlan(rows: List<LibraryRow>): CollectionAddPlan {
    val (books, others) = rows.partition { it.item.mediaType == MediaType.BOOK }
    return CollectionAddPlan(bookIds = books.map { it.item.id }, skippedPodcasts = others.size)
}

/**
 * What a batch add did, in words.
 *
 * The collection is named, because nothing on the grid changes and the line is the only
 * receipt. [held] is the number of the sent books that the collection holds after the
 * request. When it is less than the number sent, the server dropped some, and the line
 * gives both numbers rather than a count that claims all of them.
 */
internal fun collectionBatchLine(name: String?, plan: CollectionAddPlan, held: Int): String {
    val target = name?.takeIf { it.isNotBlank() } ?: "the collection"
    val sent = plan.bookIds.size
    val added = if (held >= sent) {
        "Added ${booksOf(sent)} to $target"
    } else {
        "Added $held of ${booksOf(sent)} to $target"
    }
    val skipped = plan.skippedPodcasts
    if (skipped == 0) return added
    val podcasts = if (skipped == 1) "1 podcast" else "$skipped podcasts"
    return "$added. Collections hold books only, so $podcasts stayed out"
}

/** Long enough to skip the keystrokes in a word, short enough to feel instant. */
private const val TYPING_PAUSE_MS = 150L

private fun booksOf(count: Int): String = if (count == 1) "1 book" else "$count books"

/** What the bar says when the selection has nothing a collection can hold. */
internal const val COLLECTIONS_HOLD_BOOKS = "Collections hold books only"
