package io.github.lightheaded.lugu.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.db.CollectionSummary
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.CollectionRepository
import io.github.lightheaded.lugu.core.sync.LibraryRepository
import io.github.lightheaded.lugu.core.sync.SearchEverything
import io.github.lightheaded.lugu.core.sync.SearchResults
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val results: SearchResults = SearchResults(),
    /** Collections whose name holds the words. They are server-wide, like the groups. */
    val collections: List<CollectionSummary> = emptyList(),
) {
    /** True when the results on screen answer the text in the box, and not an older one. */
    val answered: Boolean get() = results.query == query.trim()

    val nothingFound: Boolean
        get() = query.isNotBlank() && answered && results.isEmpty && collections.isEmpty()
}

/**
 * The search on Home: one box that looks through books, podcasts, episodes, authors,
 * narrators, series and collections at once.
 *
 * Everything comes from the mirror, so it works offline and answers as the words are
 * typed. The one network call is the episode pass at the start, which fetches the episode
 * lists of podcasts nobody has opened yet. Without it, the episodes of those podcasts
 * could not be found.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    authRepository: AuthRepository,
    private val libraryRepository: LibraryRepository,
    private val collectionRepository: CollectionRepository,
) : ViewModel() {

    // Not persisted, the same as the browse lists: a search is a question about right now.
    private val query = MutableStateFlow("")

    private val account: StateFlow<ActiveAccount?> =
        authRepository.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val results: Flow<SearchResults> =
        combine(account, query.debounce(TYPING_PAUSE_MS)) { current, text -> current to text }
            .flatMapLatest { (current, text) ->
                if (current == null || text.isBlank()) {
                    flowOf(SearchResults(query = text.trim()))
                } else {
                    libraryRepository.searchEverything(current, text)
                }
            }

    private val collections: Flow<List<CollectionSummary>> =
        combine(account, query.debounce(TYPING_PAUSE_MS)) { current, text -> current to text }
            .flatMapLatest { (current, text) ->
                val needle = SearchEverything.needleOf(text)
                if (current == null || needle.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    collectionRepository.observeCollections(current).map { all ->
                        all.filter { SearchEverything.contains(it.name, needle) }
                    }
                }
            }

    val state: StateFlow<SearchUiState> =
        combine(query, results, collections) { text, found, matching ->
            SearchUiState(query = text, results = found, collections = matching)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    init {
        viewModelScope.launch { fetchMissingEpisodes() }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    /** Plain server URLs; the shared OkHttp interceptor attaches auth for Coil. */
    fun coverUrl(itemId: String, width: Int = 200): String? =
        account.value?.let { "${it.baseUrl}/api/items/$itemId/cover?width=$width" }

    /**
     * Quiet, unlike the same pass on the episodes view: a search that is typed while it
     * runs still answers from what the mirror holds, and gains episodes as they arrive.
     * A failure leaves the search with what it has, which is the most a search can do.
     */
    private suspend fun fetchMissingEpisodes() {
        val current = account.filterNotNull().first()
        libraryRepository.observeLibraries(current).first()
            .filter { it.mediaType == MediaType.PODCAST }
            .forEach { library -> libraryRepository.fillEpisodeDates(current, library.id) }
    }

    private companion object {
        /** Long enough to skip the keystrokes in a word, short enough to feel instant. */
        const val TYPING_PAUSE_MS = 150L
    }
}
