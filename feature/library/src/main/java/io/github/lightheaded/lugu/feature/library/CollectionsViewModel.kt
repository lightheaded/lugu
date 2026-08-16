package io.github.lightheaded.lugu.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.db.CollectionSummary
import io.github.lightheaded.lugu.core.model.ListControls
import io.github.lightheaded.lugu.core.model.ListFacts
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.CollectionRepository
import io.github.lightheaded.lugu.core.sync.LibraryPrefs
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import java.util.Locale
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

data class CollectionsUiState(
    /** Collections after the search box has been applied — exactly what the screen draws. */
    val collections: List<CollectionSummary> = emptyList(),
    /**
     * How many the library holds before the search box.
     *
     * Both emptinesses need different words, and it is also what decides whether the search
     * box is offered at all — see [searchEarnsItsPlace].
     */
    val total: Int = 0,
    val query: String = "",
    val isSyncing: Boolean = false,
    /** Why the list may be out of date; the list itself renders from Room regardless. */
    val error: String? = null,
)

/**
 * The collections of the library in view.
 *
 * Scoped to the library the picker is on, exactly like the author and series pages, so that
 * every way into a library answers the same question. Reads come from Room, and the pull
 * from the server is a background refresh of what is already on screen rather than the
 * thing that makes it appear.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CollectionsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val collectionRepository: CollectionRepository,
    libraryPrefs: LibraryPrefs,
) : ViewModel() {

    // Not persisted, unlike the library's own sort and filter: a search is a question about
    // right now, and a box still holding last week's word looks like a broken list.
    private val query = MutableStateFlow("")

    private val syncing = MutableStateFlow(false)

    private val error = MutableStateFlow<String?>(null)

    private val account: StateFlow<ActiveAccount?> =
        authRepository.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val selectedLibraryId: Flow<String?> = libraryPrefs.settings.map { it.selectedLibraryId }

    private val collections: Flow<List<CollectionSummary>> =
        combine(account, selectedLibraryId) { current, libraryId -> current to libraryId }
            .flatMapLatest { (current, libraryId) ->
                if (current == null) {
                    flowOf(emptyList())
                } else {
                    collectionRepository.observeCollections(current, libraryId)
                }
            }

    val state: StateFlow<CollectionsUiState> =
        combine(collections, query, syncing, error) { all, text, isSyncing, failure ->
            CollectionsUiState(
                // Sorted here rather than left to SQLite's collation, so that a collection
                // called "Winter 10" lands after "Winter 2" the way a reader expects.
                collections = all
                    .filter { ListControls.matches(ListFacts(title = it.name), text) }
                    .sortedWith { a, b -> ListControls.naturalCompare(a.name, b.name) },
                total = all.size,
                query = text,
                isSyncing = isSyncing,
                error = failure,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionsUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    /**
     * Re-mirrors the collections of every library.
     *
     * Nothing on screen waits for it: the list is already rendering from Room, so a failure
     * here is a line of text rather than an empty page. [force] is what the refresh button
     * passes — the repository declines a pass it has just made, and somebody who has pressed
     * the button is entitled to say they meant it.
     */
    fun refresh(force: Boolean = false) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            syncing.value = true
            error.value = null
            collectionRepository.sync(current, force)
                .onFailure { error.value = it.message ?: "Could not reach the server" }
            syncing.value = false
        }
    }

    init {
        refresh()
    }
}

data class CollectionUiState(
    val name: String = "",
    val rows: List<LibraryRow> = emptyList(),
    /** False only until the first emission, so an empty page does not flash "nothing here". */
    val loaded: Boolean = false,
)

/**
 * One collection, and the items in it.
 *
 * The order is the collection's own and is never re-sorted on the way to the screen. That
 * order is the whole difference between a collection and a search: somebody arranged this
 * list by hand, and putting it back into alphabetical order would throw away the only thing
 * the page has to say.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CollectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
    private val collectionRepository: CollectionRepository,
    progressRepository: ProgressRepository,
) : ViewModel() {

    private val collectionId: String = checkNotNull(savedStateHandle["collectionId"])

    private val account: StateFlow<ActiveAccount?> =
        authRepository.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<CollectionUiState> = account
        .flatMapLatest { current ->
            if (current == null) {
                flowOf(CollectionUiState())
            } else {
                combine(
                    collectionRepository.observeItems(current, collectionId),
                    progressRepository.observeAll(current),
                    // The name comes from the same mirrored list the previous screen drew,
                    // so a rename made on the server reaches the title bar on the next sync
                    // without a fetch of its own.
                    collectionRepository.observeCollections(current),
                ) { items, progress, collections ->
                    val byItem = ItemProgress.byItem(progress)
                    CollectionUiState(
                        name = collections.firstOrNull { it.id == collectionId }?.name.orEmpty(),
                        rows = items.map { LibraryRow(it, byItem[it.id]) },
                        loaded = true,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionUiState())

    /** Plain server URLs; the shared OkHttp interceptor attaches auth for Coil. */
    fun coverUrl(itemId: String, width: Int = 400): String? =
        account.value?.let { "${it.baseUrl}/api/items/$itemId/cover?width=$width" }

    init {
        // Opening a collection is the moment its membership matters most, and it is the one
        // list in the app somebody else can have changed since the last sync. Just this one
        // is pulled, not the whole set: the single-collection endpoint is a fraction of the
        // size, and it is the one that respects what this reader is allowed to see.
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            collectionRepository.refresh(current, collectionId)
        }
    }
}

/**
 * "12 books", "1 book", or "Empty".
 *
 * A count is what makes a list of collections usable — it separates one worth opening from
 * one somebody started and abandoned — and an empty collection is worth naming outright
 * rather than reporting as "0 books", which reads as a failure to load.
 *
 * The noun is fixed rather than following the library's media type because the server keeps
 * collections for book libraries only; a collection of podcasts is not a thing that exists.
 */
internal fun collectionCountLine(count: Int): String = when {
    count <= 0 -> "Empty"
    count == 1 -> "1 book"
    else -> "%,d books".format(Locale.UK, count)
}

/**
 * Why the list is empty, told apart.
 *
 * A server with no collections on it and a search that matched none of them look identical
 * on screen, and the fix for each is the opposite of the fix for the other. The first case
 * also has to say where collections come from: this app can add a book to one but cannot
 * make one, so somebody looking at an empty page needs to be told where to go.
 */
internal fun emptyCollectionsLine(total: Int, query: String): String = when {
    total == 0 -> "No collections in this library yet. They are made on the server, and " +
        "appear here once they exist."
    query.isBlank() -> "Nothing to show."
    else -> "No collections match that."
}

/**
 * Whether a search box is worth the room it takes.
 *
 * Most libraries have a handful of collections, and a box above four rows is furniture: it
 * costs a line of the screen and answers a question nobody was going to ask. Measured
 * against the unfiltered total so that typing can never make the box disappear underneath
 * the fingers holding it.
 */
internal fun searchEarnsItsPlace(total: Int): Boolean = total > SEARCH_EARNS_ITS_PLACE

/** About a screenful. Past this, finding one by eye stops being quicker than typing. */
private const val SEARCH_EARNS_ITS_PLACE = 8
