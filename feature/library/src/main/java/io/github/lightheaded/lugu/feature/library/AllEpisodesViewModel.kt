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
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import io.github.lightheaded.lugu.core.sync.QueueRepository
import io.github.lightheaded.lugu.core.ui.Status
import javax.inject.Inject
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
) : ViewModel() {

    // Not persisted, unlike the sort and the filter: a search is a question about right now.
    private val query = MutableStateFlow("")

    private val status = MutableStateFlow<Status?>(null)

    private val account: StateFlow<ActiveAccount?> =
        authRepository.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The podcast library this list is for: the one the picker is on, if it holds podcasts.
     *
     * Otherwise the first podcast library. The link that opens this screen shows only in a
     * podcast library, so the fallback matters only if the picker moves while this is open.
     */
    private val library: Flow<Library?> = combine(
        account.flatMapLatest { current ->
            if (current == null) flowOf(emptyList()) else libraryRepository.observeLibraries(current)
        },
        libraryPrefs.settings.map { it.selectedLibraryId }.distinctUntilChanged(),
    ) { libraries, selected ->
        val podcasts = libraries.filter { it.mediaType == MediaType.PODCAST }
        podcasts.firstOrNull { it.id == selected } ?: podcasts.firstOrNull()
    }.distinctUntilChanged()

    private val rows: Flow<Pair<Library?, List<AllEpisodesRow>>> =
        combine(account, library) { current, lib -> current to lib }
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AllEpisodesUiState())

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

    fun removeDownload(row: AllEpisodesRow) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            downloadRepository.remove(current, row.itemId, row.episodeId)
            status.value = Status.Done("Removed ${row.row.episode.title}".trim())
        }
    }

    fun playNext(row: AllEpisodesRow) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            queueRepository.addNext(current, row.itemId, row.episodeId)
            status.value = Status.Done("Playing next")
        }
    }

    fun addToQueue(row: AllEpisodesRow) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            queueRepository.addLast(current, row.itemId, row.episodeId)
            status.value = Status.Done("Added to the queue")
        }
    }

    fun setFinished(row: AllEpisodesRow, isFinished: Boolean) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            progressRepository.setFinished(
                current,
                row.itemId,
                row.episodeId,
                isFinished,
                row.row.episode.durationSec,
            )
            status.value = Status.Done(if (isFinished) "Marked as finished" else "Marked as not finished")
        }
    }

    fun dismissStatus() {
        status.value = null
    }
}
