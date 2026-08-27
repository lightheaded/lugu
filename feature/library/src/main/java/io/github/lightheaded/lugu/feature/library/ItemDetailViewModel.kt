package io.github.lightheaded.lugu.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.db.CollectionSummary
import io.github.lightheaded.lugu.core.db.DownloadEntity
import io.github.lightheaded.lugu.core.download.DownloadRepository
import io.github.lightheaded.lugu.core.download.DownloadStatus
import io.github.lightheaded.lugu.core.model.EpisodeSort
import io.github.lightheaded.lugu.core.model.LibraryItem
import io.github.lightheaded.lugu.core.model.ListControls
import io.github.lightheaded.lugu.core.model.ListFacts
import io.github.lightheaded.lugu.core.model.ListFilter
import io.github.lightheaded.lugu.core.model.MediaProgress
import io.github.lightheaded.lugu.core.model.PodcastEpisode
import io.github.lightheaded.lugu.core.model.PodcastTrim
import io.github.lightheaded.lugu.core.model.SeriesRef
import io.github.lightheaded.lugu.core.model.WebClient
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.CollectionRepository
import io.github.lightheaded.lugu.core.sync.LibraryPrefs
import io.github.lightheaded.lugu.core.sync.LibraryRepository
import io.github.lightheaded.lugu.core.sync.PlaybackPrefs
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
import kotlinx.coroutines.launch

data class EpisodeRow(
    val episode: PodcastEpisode,
    val progress: MediaProgress?,
    val download: DownloadStatus? = null,
) {
    val progressFraction: Float
        get() = progress?.progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f

    /** Finished is a flag of its own, not "near enough to the end": the two disagree often. */
    val isFinished: Boolean
        get() = progress?.isFinished == true

    /**
     * The row as the shared sort and filter see it.
     *
     * Stated here rather than at the point of sorting so that "finished" and "downloaded"
     * mean one thing for an episode wherever the question is asked — the list, the count
     * line, and whatever asks next.
     */
    internal val facts: ListFacts
        get() = ListFacts(
            title = episode.title,
            secondary = episode.subtitle,
            publishedAtMs = episode.publishedAtMs,
            durationSec = episode.durationSec,
            progressFraction = progressFraction,
            isFinished = isFinished,
            isDownloaded = download?.isComplete == true,
        )
}

/**
 * Which rows are picked out, and whether the list is in the mode where picking happens.
 *
 * The mode is held apart from the set because the two ways in differ. The episode list
 * enters by long-press, with one row already chosen; the queue enters from the app bar,
 * with nothing chosen yet, because long-press there already means drag. A mode inferred
 * from "at least one row is chosen" would collapse under the second case, and would also
 * throw someone out of the mode the moment they unpicked their last row. Leaving is
 * therefore always an explicit act.
 */
internal data class Selection(
    val active: Boolean = false,
    val ids: Set<String> = emptySet(),
) {
    fun entered(): Selection = copy(active = true)

    fun toggle(id: String): Selection =
        Selection(active = true, ids = if (id in ids) ids - id else ids + id)

    /** Select-all means all *visible* rows: after filtering, that is the useful promise. */
    fun selectAll(visible: Collection<String>): Selection =
        Selection(active = true, ids = visible.toSet())

    /**
     * Rows a filter or a deletion has taken off the screen stop counting.
     *
     * Without this the bar offers to act on rows nobody can see, and the count disagrees
     * with the ticks. The set itself is left alone, so clearing the filter brings a
     * hidden choice back rather than quietly discarding it.
     */
    fun retaining(visible: Collection<String>): Selection =
        if (!active) this else copy(ids = ids.intersect(visible.toSet()))

    fun cleared(): Selection = Selection()
}

data class ItemDetailUiState(
    val item: LibraryItem? = null,
    /** Episodes after the search, the filter and the sort — exactly what the screen draws. */
    val episodes: List<EpisodeRow> = emptyList(),
    /** How many episodes the feed holds, so the count line can say what was hidden. */
    val episodeCount: Int = 0,
    val query: String = "",
    val episodeSort: EpisodeSort = EpisodeSort.NEWEST,
    val episodeFilter: ListFilter = ListFilter.ALL,
    val selectionActive: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val progressFraction: Float = 0f,
    val positionSec: Double = 0.0,
    /** The book's own finished flag; a podcast's lives on each episode row instead. */
    val isFinished: Boolean = false,
    val coverUrl: String? = null,
    /**
     * This item's page in the server's own web client.
     *
     * Here rather than built in the screen because it needs the server address, and a screen
     * that knows the address is a screen that has to be given an account. Null until one is
     * signed in, which is also when there is nothing to link to.
     */
    val webUrl: String? = null,
    /** The item-level download; podcasts carry theirs per episode instead. */
    val download: DownloadStatus? = null,
    /** The collections of this item's library, each saying whether it holds this item. */
    val collections: List<CollectionChoice> = emptyList(),
    /**
     * Every series this book is in, one entry each.
     *
     * Read from the membership table rather than from `LibraryItem.seriesName`, which is
     * the server's *rendering* of this list: it joins all of a book's series into one
     * string, so a book in two of them used to draw a single phantom series named after
     * both, carrying the second one's number.
     */
    val series: List<SeriesRef> = emptyList(),
    /** What this show trims from each episode — its own, or the default it is following. */
    val trim: PodcastTrim = PodcastTrim.NONE,
    /**
     * Whether [trim] belongs to this show or is the default showing through.
     *
     * Held apart from the values because the two states are indistinguishable from them: a
     * show explicitly trimmed to nothing and a show following a default of nothing carry
     * the same numbers and behave differently the moment the default changes.
     */
    val trimIsOwn: Boolean = false,
    val message: String? = null,
)

/** One collection, and whether this item is in it. */
data class CollectionChoice(val id: String, val name: String, val contains: Boolean)

/**
 * A download delete or cancel that has happened and can still be taken back.
 *
 * Two shapes, because the two actions leave different things behind. Deleting a
 * completed download only marks it pending-delete: nothing has left disk, so
 * [Deferred] carries the exact stored row and undoing it is a plain restore. Cancelling
 * a download still running frees its partial bytes at once — there is no safe
 * pending state for a file the engine itself is still writing to — so [Cancelled]
 * carries only what is needed to ask for the file again; undo re-enqueues it from
 * nothing, which is what FEEDBACK.md calls out as the honest answer here, rather than
 * pretending a resume is a restore.
 */
internal sealed class DownloadUndo(val text: String) {
    class Deferred(val snapshot: DownloadEntity, text: String) : DownloadUndo(text)
    class Cancelled(val episodeId: String?, text: String) : DownloadUndo(text)
}

/**
 * The page's facts that come from neither the mirror nor the list controls.
 *
 * Carried as one value so the state can be assembled from five flows rather than seven —
 * `combine` stops taking named parameters past five, and the alternative is an array of
 * `Any?` and a row of casts.
 */
private data class ItemDetailExtras(
    val collections: List<CollectionChoice> = emptyList(),
    val series: List<SeriesRef> = emptyList(),
    val trim: PodcastTrim = PodcastTrim.NONE,
    val trimIsOwn: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ItemDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
    private val libraryRepository: LibraryRepository,
    private val progressRepository: ProgressRepository,
    private val downloadRepository: DownloadRepository,
    private val queueRepository: QueueRepository,
    private val collectionRepository: CollectionRepository,
    private val libraryPrefs: LibraryPrefs,
    private val playbackPrefs: PlaybackPrefs,
) : ViewModel() {

    private val itemId: String = checkNotNull(savedStateHandle["itemId"])

    private val message = MutableStateFlow<String?>(null)

    // Not persisted, unlike the sort and the filter: a search is a question about right
    // now, and a box still full of last week's word is a list that looks broken on open.
    private val query = MutableStateFlow("")

    private val selection = MutableStateFlow(Selection())

    private val downloadUndo = MutableStateFlow<DownloadUndo?>(null)

    /** The last download delete or cancel, while it can still be taken back. */
    internal val undo: StateFlow<DownloadUndo?> = downloadUndo

    /**
     * How long a download undo stays offered, from the same setting the player's own
     * notices use. One setting decides how long lugu leaves a way back, wherever the way
     * back is.
     */
    internal val noticeMillis: StateFlow<Long> = playbackPrefs.settings
        .map { it.noticeSeconds.coerceAtLeast(1) * 1_000L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 10_000L)

    private val account: StateFlow<ActiveAccount?> =
        authRepository.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val content: Flow<ItemDetailUiState> = account
        .flatMapLatest { current ->
            if (current == null) {
                flowOf(ItemDetailUiState())
            } else {
                combine(
                    libraryRepository.observeItem(current, itemId),
                    libraryRepository.observeEpisodes(current, itemId),
                    progressRepository.observeAll(current),
                    downloadRepository.observeForItem(current, itemId),
                    message,
                ) { item, episodes, progress, downloads, note ->
                    val byKey = progress.associateBy { "${it.libraryItemId}#${it.episodeId.orEmpty()}" }
                    val downloadsByEpisode = downloads.associateBy { it.episodeId.orEmpty() }
                    val itemProgress = byKey["$itemId#"]
                    ItemDetailUiState(
                        item = item,
                        episodes = episodes.map {
                            EpisodeRow(it, byKey["$itemId#${it.id}"], downloadsByEpisode[it.id])
                        },
                        progressFraction = itemProgress?.progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f,
                        positionSec = itemProgress?.currentTimeSec ?: 0.0,
                        isFinished = itemProgress?.isFinished == true,
                        coverUrl = "${current.baseUrl}/api/items/$itemId/cover?width=600",
                        webUrl = WebClient.item(current.baseUrl, itemId),
                        download = downloadsByEpisode[""],
                        message = note,
                    )
                }
            }
        }

    /**
     * The collections this item could be in, and which of them it is in.
     *
     * Scoped to the item's own library, because the server refuses a book offered to a
     * collection in another one — a choice that cannot work is worse than no choice.
     */
    private val collectionChoices: Flow<List<CollectionChoice>> = account
        .flatMapLatest { current ->
            if (current == null) {
                flowOf(emptyList())
            } else {
                libraryRepository.observeItem(current, itemId).flatMapLatest { item ->
                    if (item == null) {
                        flowOf(emptyList())
                    } else {
                        combine(
                            collectionRepository.observeCollections(current, item.libraryId),
                            collectionRepository.observeMembership(current, itemId),
                        ) { collections, membership -> collectionChoices(collections, membership) }
                    }
                }
            }
        }

    /**
     * Whether this show has a trim of its own rather than the default showing through.
     *
     * `hasOwnTrim` answers once rather than as a flow, so the answer is taken when the page
     * opens and again after every change made from it. That is enough to keep it true:
     * this page is the only place a show's own trim is set or cleared, and a listener who
     * sets one has to be able to see that they have, or "trim nothing" and "follow the
     * default" become the same-looking state.
     */
    private val trimIsOwn = MutableStateFlow(false)

    private val seriesMemberships: Flow<List<SeriesRef>> = account
        .flatMapLatest { current ->
            if (current == null) flowOf(emptyList()) else libraryRepository.observeSeriesFor(current, itemId)
        }

    private val extras: Flow<ItemDetailExtras> = combine(
        collectionChoices,
        seriesMemberships,
        playbackPrefs.observeTrimFor(itemId),
        trimIsOwn,
    ) { choices, series, trim, isOwn -> ItemDetailExtras(choices, series, trim, isOwn) }

    /**
     * The list is narrowed here rather than in the screen.
     *
     * A composable that filters recomputes the answer on every recomposition and has
     * nowhere to put the count, and the selection has to be reconciled against the
     * surviving rows anyway — which is a data question, not a drawing one.
     */
    val state: StateFlow<ItemDetailUiState> = combine(
        content,
        query,
        libraryPrefs.settings,
        selection,
        extras,
    ) { base, search, settings, picked, extra ->
        val visible = ListControls.sortEpisodes(
            base.episodes.filter {
                ListControls.matches(it.facts, settings.episodeFilter) &&
                    ListControls.matches(it.facts, search)
            },
            settings.episodeSort,
        ) { it.facts }
        val onScreen = picked.retaining(visible.map { it.episode.id })
        base.copy(
            episodes = visible,
            episodeCount = base.episodes.size,
            query = search,
            episodeSort = settings.episodeSort,
            episodeFilter = settings.episodeFilter,
            selectionActive = onScreen.active,
            selectedIds = onScreen.ids,
            collections = extra.collections,
            series = extra.series,
            trim = extra.trim,
            trimIsOwn = extra.trimIsOwn,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ItemDetailUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    /**
     * The ordering and the filter outlive the screen.
     *
     * Someone who has put a thousand-episode feed into oldest-first is working through it
     * from the start, and having to say so again on every visit is the friction that ends
     * with them not bothering.
     */
    fun setSort(sort: EpisodeSort) {
        viewModelScope.launch { libraryPrefs.setEpisodeSort(sort) }
    }

    fun setFilter(filter: ListFilter) {
        viewModelScope.launch { libraryPrefs.setEpisodeFilter(filter) }
    }

    fun toggleSelection(episodeId: String) {
        selection.value = selection.value.toggle(episodeId)
    }

    fun selectAllVisible() {
        selection.value = selection.value.selectAll(state.value.episodes.map { it.episode.id })
    }

    fun clearSelection() {
        selection.value = selection.value.cleared()
    }

    /**
     * Starts a download, and says why when it will not.
     *
     * The refusals worth surfacing are the ones a person can act on — the storage cap,
     * or an item the server has no audio for. Failing silently on a button press is the
     * behaviour that makes people press it four more times.
     */
    fun download(episodeId: String? = null) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            downloadRepository.download(current, itemId, episodeId)
                .onFailure { message.value = it.message ?: "Could not start the download" }
        }
    }

    /**
     * Deletes a completed download, or cancels one still running — and either way,
     * offers a tap back.
     *
     * A completed download is only marked pending-delete: nothing leaves disk, so the
     * control reads as not downloaded at once, and an undo inside the notice window is
     * an exact, instant restore, because nothing was touched. A download still running
     * has no safe pending state to sit in — the engine's own reconciler folds a file
     * that is still complete on disk straight back to `completed` the moment it looks
     * again — so tapping it cancels for real at once, and its undo re-enqueues the
     * download from nothing rather than pretending to resume it.
     */
    fun removeDownload(episodeId: String? = null) {
        val current = state.value
        val row = if (episodeId == null) {
            current.download
        } else {
            current.episodes.firstOrNull { it.episode.id == episodeId }?.download
        }
        val title = if (episodeId == null) {
            current.item?.title
        } else {
            current.episodes.firstOrNull { it.episode.id == episodeId }?.episode?.title
        }.orEmpty()

        viewModelScope.launch {
            val account = authRepository.account() ?: return@launch
            if (row?.isComplete == true) {
                val snapshot = downloadRepository.deferDelete(account, itemId, episodeId)
                if (snapshot != null) downloadUndo.value = DownloadUndo.Deferred(snapshot, "Deleted $title".trim())
            } else {
                downloadRepository.remove(account, itemId, episodeId)
                downloadUndo.value = DownloadUndo.Cancelled(episodeId, "Cancelled $title".trim())
            }
        }
    }

    /** Puts back whatever the last delete or cancel took away. */
    fun undoDownload() {
        val pending = downloadUndo.value ?: return
        downloadUndo.value = null
        viewModelScope.launch {
            val account = authRepository.account() ?: return@launch
            when (pending) {
                is DownloadUndo.Deferred -> downloadRepository.restoreDeferred(account, pending.snapshot)
                is DownloadUndo.Cancelled ->
                    downloadRepository.download(account, itemId, pending.episodeId)
                        .onFailure { message.value = it.message ?: "Could not start the download" }
            }
        }
    }

    /**
     * Lets a delete or cancel stand once the notice window closes with no undo.
     *
     * A cancelled download already left nothing behind, so there is nothing left to do
     * for it. A deferred delete still has its files on disk, so this is where they
     * finally go.
     */
    fun dismissDownloadUndo() {
        val pending = downloadUndo.value ?: return
        downloadUndo.value = null
        if (pending !is DownloadUndo.Deferred) return
        viewModelScope.launch {
            val account = authRepository.account() ?: return@launch
            downloadRepository.finalizeDeferred(account, pending.snapshot)
        }
    }

    /**
     * Queueing confirms itself.
     *
     * The queue is on another screen, so without a word here the button looks like it
     * did nothing — and the second press would be read as a second copy if the queue
     * were not already careful about that.
     */
    fun playNext(episodeId: String? = null) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            queueRepository.addNext(current, itemId, episodeId)
            message.value = "Playing next"
        }
    }

    fun addToQueue(episodeId: String? = null) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            queueRepository.addLast(current, itemId, episodeId)
            message.value = "Added to the queue"
        }
    }

    /**
     * Marks a book or one episode finished, or takes the mark off again.
     *
     * The duration is handed over as a fallback because a book nobody has opened has no
     * progress row to take one from, and a finished book with no duration is a hundred per
     * cent of nothing — the shelves and the filters would both disbelieve it.
     *
     * Un-finishing also puts the position back to the start, which is what the server's own
     * web client does. Worth knowing before pressing it: someone who un-finishes a book to
     * get it back into their in-progress list loses their place in it.
     */
    fun setFinished(isFinished: Boolean, episodeId: String? = null) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            val duration = if (episodeId == null) {
                state.value.item?.durationSec ?: 0.0
            } else {
                state.value.episodes.firstOrNull { it.episode.id == episodeId }?.episode?.durationSec ?: 0.0
            }
            progressRepository.setFinished(current, itemId, episodeId, isFinished, duration)
            message.value = if (isFinished) "Marked as finished" else "Marked as not finished"
        }
    }

    /**
     * The same, for everything picked.
     *
     * One direction for the whole selection rather than a toggle per row: a batch that
     * finished some rows and un-finished others would depend on state nobody can see from
     * the bar, and could not be undone by pressing it again.
     */
    fun setSelectedFinished(isFinished: Boolean) {
        val chosen = selectedRows()
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            chosen.forEach {
                progressRepository.setFinished(
                    current,
                    itemId,
                    it.episode.id,
                    isFinished,
                    it.episode.durationSec,
                )
            }
            message.value = if (isFinished) {
                "Marked ${countOf(chosen.size)} as finished"
            } else {
                "Marked ${countOf(chosen.size)} as not finished"
            }
            clearSelection()
        }
    }

    /**
     * The whole point of picking eight episodes is not pressing download eight times.
     *
     * Only the first refusal is reported. The cap is the usual reason a batch stops, and
     * once it is reached every remaining episode gives the same answer — eight identical
     * snackbars would say nothing the first one did not.
     */
    fun downloadSelected() {
        val chosen = selectedRows()
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            var refusal: String? = null
            chosen.forEach { row ->
                downloadRepository.download(current, itemId, row.episode.id)
                    .onFailure { failure -> refusal = refusal ?: failure.message }
            }
            message.value = refusal ?: "Downloading ${countOf(chosen.size)}"
            clearSelection()
        }
    }

    fun removeSelectedDownloads() {
        val chosen = selectedRows().filter { it.download != null }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            chosen.forEach { downloadRepository.remove(current, itemId, it.episode.id) }
            message.value = "Removed ${countOf(chosen.size)}"
            clearSelection()
        }
    }

    fun addSelectedToQueue() {
        val chosen = selectedRows()
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            chosen.forEach { queueRepository.addLast(current, itemId, it.episode.id) }
            message.value = "Added ${countOf(chosen.size)} to the queue"
            clearSelection()
        }
    }

    /**
     * Added back to front, because each one goes to the head of the queue.
     *
     * Sent in list order, the last episode picked would end up playing first, which is
     * not what "play next" says on a list someone has just read top to bottom.
     */
    fun playSelectedNext() {
        val chosen = selectedRows()
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            chosen.reversed().forEach { queueRepository.addNext(current, itemId, it.episode.id) }
            message.value = "Playing ${countOf(chosen.size)} next"
            clearSelection()
        }
    }

    /**
     * Re-mirrors the collections behind the membership list.
     *
     * Called when the list is opened rather than when the page is, because the pull is the
     * heaviest request in the app and almost nobody who opens a book page wants it. A
     * failure says nothing: the list still renders from Room, and an edit attempted on stale
     * membership reports its own refusal in words.
     */
    fun refreshCollections() {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            collectionRepository.sync(current)
        }
    }

    /**
     * Puts this item into a collection, or takes it out again.
     *
     * Both directions go to the server and are believed only once it answers. Nothing is
     * applied optimistically: a collection is shared, and a tick that appeared locally and
     * then quietly failed would be a lie about a list other people are reading.
     */
    fun setInCollection(collectionId: String, inCollection: Boolean) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            val name = state.value.collections.firstOrNull { it.id == collectionId }?.name
            val result = if (inCollection) {
                collectionRepository.add(current, collectionId, itemId)
            } else {
                collectionRepository.remove(current, collectionId, itemId)
            }
            message.value = result.fold(
                onSuccess = { collectionChangeLine(name, inCollection) },
                onFailure = { it.message ?: "Could not change the collection" },
            )
        }
    }

    /**
     * Gives this show a trim of its own, whatever the numbers in it are.
     *
     * Setting every field back to nothing is still a decision about this show, and it is
     * kept as one: a listener who turns an inherited fifteen-second intro off means "not on
     * this one", not "go back to whatever the default says next week".
     */
    fun setTrim(trim: PodcastTrim) {
        viewModelScope.launch {
            playbackPrefs.setTrimFor(itemId, trim)
            trimIsOwn.value = true
        }
    }

    /** Hands the show back to the default, which is the only way out of [setTrim]. */
    fun useDefaultTrim() {
        viewModelScope.launch {
            playbackPrefs.clearTrimFor(itemId)
            trimIsOwn.value = false
        }
    }

    fun dismissMessage() {
        message.value = null
    }

    /** Read from the visible list, so a hidden row can never be acted on unseen. */
    private fun selectedRows(): List<EpisodeRow> =
        state.value.let { current -> current.episodes.filter { it.episode.id in current.selectedIds } }

    private fun countOf(count: Int): String = if (count == 1) "1 episode" else "$count episodes"

    init {
        // The list payload is minified; chapters and episodes only arrive with the
        // expanded fetch, so pull it once on open and let Room feed the screen.
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            libraryRepository.syncItemDetail(current, itemId)
        }
        viewModelScope.launch { trimIsOwn.value = playbackPrefs.hasOwnTrim(itemId) }
    }
}

/**
 * The collections of a library, each marked with whether it holds this item.
 *
 * Ordered by name and never by whether the box is ticked. Sorting the ticked ones to the
 * top would rearrange the list underneath the finger that has just ticked one, and the row
 * that moves into the vacated place is the row that gets tapped by mistake.
 */
internal fun collectionChoices(
    collections: List<CollectionSummary>,
    membership: Set<String>,
): List<CollectionChoice> = collections
    .map { CollectionChoice(id = it.id, name = it.name, contains = it.id in membership) }
    .sortedWith { a, b -> ListControls.naturalCompare(a.name, b.name) }

/**
 * What just happened, named.
 *
 * The collection is named rather than called "the collection", because this is the one
 * action on the page whose effect is invisible from it: nothing on the book's own page
 * changes, and the confirmation is all there is to go on. A collection the mirror has no
 * name for is described in general terms rather than as a blank.
 */
internal fun collectionChangeLine(name: String?, added: Boolean): String {
    val target = name?.takeIf { it.isNotBlank() } ?: "the collection"
    return if (added) "Added to $target" else "Removed from $target"
}
