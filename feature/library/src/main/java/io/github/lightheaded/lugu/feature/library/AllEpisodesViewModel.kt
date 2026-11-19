package io.github.lightheaded.lugu.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.download.DownloadRepository
import io.github.lightheaded.lugu.core.model.EpisodeSort
import io.github.lightheaded.lugu.core.model.Library
import io.github.lightheaded.lugu.core.model.ListControls
import io.github.lightheaded.lugu.core.model.ListFacts
import io.github.lightheaded.lugu.core.model.ListFilter
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.LibraryPrefs
import io.github.lightheaded.lugu.core.sync.LibraryRepository
import io.github.lightheaded.lugu.core.sync.PlaybackPrefs
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import io.github.lightheaded.lugu.core.sync.QueueRepository
import io.github.lightheaded.lugu.core.ui.Status
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One episode in the list that spans podcasts, and the podcast it belongs to. */
data class AllEpisodesRow(
    val row: EpisodeRow,
    val podcastTitle: String,
) {
    val itemId: String get() = row.episode.libraryItemId
    val episodeId: String get() = row.episode.id

    /** Unique across podcasts, so the list can key on it. */
    val key: String get() = "$itemId#$episodeId"

    /**
     * The episode's own facts, with the podcast as the secondary field.
     *
     * The podcast title is what a search box over many shows is asked for most, so the
     * search reaches it here. One podcast's page uses the subtitle instead, because there
     * every row has the same podcast.
     */
    internal val facts: ListFacts
        get() = row.facts.copy(secondary = podcastTitle)
}

data class AllEpisodesUiState(
    /** The library in view, so the bar can say whose episodes these are. */
    val libraryName: String = "",
    /** Episodes after the search, the filter and the sort — exactly what the screen draws. */
    val episodes: List<AllEpisodesRow> = emptyList(),
    /** How many episodes the mirror holds for this library, before the search and filter. */
    val episodeCount: Int = 0,
    val query: String = "",
    val sort: EpisodeSort = EpisodeSort.NEWEST,
    val filter: ListFilter = ListFilter.ALL,
    val status: Status? = null,
    /** False only until the first emission, so an empty list does not flash "nothing here". */
    val loaded: Boolean = false,
)

/**
 * The episodes of every podcast in the library in view, in one list.
 *
 * The same sort and filter as one podcast's page, applied across all of them, so "newest
 * first" here answers "what came out lately, from anything I follow".
 *
 * The mirror holds the episodes only of the podcasts that somebody opened, or that the
 * latest-episode sort fetched. So the screen fetches the rest when it opens, with the same
 * pass that sort uses. The pass skips every podcast whose episode count already matches
 * the server's, so after the first time it costs a request only per podcast that gained
 * episodes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AllEpisodesViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val libraryRepository: LibraryRepository,
    private val progressRepository: ProgressRepository,
    private val downloadRepository: DownloadRepository,
    private val queueRepository: QueueRepository,
    private val libraryPrefs: LibraryPrefs,
    playbackPrefs: PlaybackPrefs,
) : ViewModel() {

    // Not persisted, unlike the sort and the filter: a search is a question about right now.
    private val query = MutableStateFlow("")

    /**
     * The search box's own text, straight from the source. [state] is built off the main
     * thread, and a text field fed from an async copy drops keystrokes.
     */
    internal val queryText: StateFlow<String> = query

    /** Progress of the fetch, and its failures: a state, so it is the strip's. */
    private val status = MutableStateFlow<Status?>(null)

    private val note = MutableStateFlow<String?>(null)

    /** A short confirmation for the snackbar, which shows it over the list. */
    internal val message: StateFlow<String?> = note

    private val undos = RowUndos(
        viewModelScope,
        authRepository,
        downloadRepository,
        progressRepository,
        onMessage = { note.value = it },
    )

    /** The last change that can be taken back, while it still can. */
    internal val undo: StateFlow<RowUndo?> = undos.current

    internal val noticeMillis: StateFlow<Long> = noticeMillisOf(playbackPrefs)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 10_000L)

    private val account: StateFlow<ActiveAccount?> =
        authRepository.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The podcast library this list is for: the one the picker is on, if it holds podcasts.
     *
     * Otherwise the first podcast library. The link that opens this screen shows only in a
     * podcast library, so the fallback matters only if the picker moves while this is open.
     */
    // Read from the repository, not from [account]: that one starts as null before the
    // real answer, and a null there would count as "loaded, and empty".
    private val library: Flow<Library?> = combine(
        authRepository.observeAccount().flatMapLatest { current ->
            if (current == null) flowOf(emptyList()) else libraryRepository.observeLibraries(current)
        },
        libraryPrefs.settings.map { it.selectedLibraryId }.distinctUntilChanged(),
    ) { libraries, selected ->
        val podcasts = libraries.filter { it.mediaType == MediaType.PODCAST }
        podcasts.firstOrNull { it.id == selected } ?: podcasts.firstOrNull()
    }.distinctUntilChanged()

    private val rows: Flow<Pair<Library?, List<AllEpisodesRow>>> =
        combine(authRepository.observeAccount(), library) { current, lib -> current to lib }
            .flatMapLatest { (current, lib) ->
                if (current == null || lib == null) {
                    flowOf(lib to emptyList())
                } else {
                    combine(
                        libraryRepository.observeLibraryEpisodes(current, lib.id),
                        progressRepository.observeAll(current),
                        downloadRepository.observeAll(current),
                    ) { episodes, progress, downloads ->
                        val progressByKey = progress.associateBy { "${it.libraryItemId}#${it.episodeId.orEmpty()}" }
                        val downloadByKey = downloads.associateBy { "${it.libraryItemId}#${it.episodeId.orEmpty()}" }
                        lib to episodes.map { found ->
                            val key = "${found.episode.libraryItemId}#${found.episode.id}"
                            AllEpisodesRow(
                                row = EpisodeRow(found.episode, progressByKey[key], downloadByKey[key]),
                                podcastTitle = found.podcastTitle,
                            )
                        }
                    }
                }
            }

    val state: StateFlow<AllEpisodesUiState> = combine(
        rows,
        query,
        libraryPrefs.settings,
        status,
    ) { mirrored, search, settings, line ->
        val (lib, all) = mirrored
        val visible = ListControls.sortEpisodes(
            all.filter {
                ListControls.matches(it.facts, settings.allEpisodesFilter) &&
                    ListControls.matches(it.facts, search)
            },
            settings.allEpisodesSort,
        ) { it.facts }
        AllEpisodesUiState(
            libraryName = lib?.name.orEmpty(),
            episodes = visible,
            episodeCount = all.size,
            query = search,
            sort = settings.allEpisodesSort,
            filter = settings.allEpisodesFilter,
            status = line,
            loaded = true,
        )
    }
        // Filtering and sorting a whole library's episodes is too much work for the main
        // thread on every keystroke.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AllEpisodesUiState())

    init {
        viewModelScope.launch {
            val current = account.filterNotNull().first()
            val lib = library.filterNotNull().first()
            fetchMissingEpisodes(current, lib)
        }
    }

    /**
     * Fetches the episodes of each podcast that the mirror holds only in part.
     *
     * The list shows what the mirror holds at once and grows as the pass goes. A failure
     * leaves the rows that came in, and says that some are missing.
     */
    private suspend fun fetchMissingEpisodes(current: ActiveAccount, lib: Library) {
        var shown = false
        libraryRepository.fillEpisodeDates(current, lib.id) { done, total ->
            shown = true
            status.value = Status.Working("Fetching episodes — $done of $total", done.toFloat() / total)
        }.onFailure {
            status.value = Status.Problem(it.message ?: "Could not fetch all the episodes")
            return
        }
        if (shown) status.value = null
    }

    fun setQuery(value: String) {
        query.value = value
    }

    /** Stored apart from one podcast's choice. See [LibraryPrefs] for why. */
    fun setSort(sort: EpisodeSort) {
        viewModelScope.launch { libraryPrefs.setAllEpisodesSort(sort) }
    }

    fun setFilter(filter: ListFilter) {
        viewModelScope.launch { libraryPrefs.setAllEpisodesFilter(filter) }
    }

    fun download(row: AllEpisodesRow) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            downloadRepository.download(current, row.itemId, row.episodeId)
                .onFailure { status.value = Status.Problem(it.message ?: "Could not start the download") }
        }
    }

    /** Takes the download away at once, as on the podcast page; the snackbar offers it back. */
    fun removeDownload(row: AllEpisodesRow) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            undos.removeDownload(current, row.itemId, row.episodeId, row.row.download)
        }
    }

    fun playNext(row: AllEpisodesRow) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            queueRepository.addNext(current, row.itemId, row.episodeId)
            note.value = "Playing next"
        }
    }

    fun addToQueue(row: AllEpisodesRow) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            queueRepository.addLast(current, row.itemId, row.episodeId)
            note.value = "Added to the queue"
        }
    }

    /** Undo in the snackbar puts the position back too, not only the mark. */
    fun setFinished(row: AllEpisodesRow, isFinished: Boolean) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            undos.setFinished(current, row.itemId, row.episodeId, isFinished, row.row.episode.durationSec)
        }
    }

    internal fun undo(shown: RowUndo) = undos.undo(shown)

    internal fun dismissUndo(shown: RowUndo) = undos.dismiss(shown)

    /** Starting an episode here settles a finished-mark undo for it. */
    fun onPlayStarted(row: AllEpisodesRow) = undos.dropProgressUndo(row.itemId, row.episodeId)

    override fun onCleared() {
        undos.settleOnClose()
    }

    fun dismissMessage() {
        note.value = null
    }

    fun dismissStatus() {
        status.value = null
    }
}
