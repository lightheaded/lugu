package io.github.lightheaded.lugu.feature.library

import android.util.Base64
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.db.BrowseGroup
import io.github.lightheaded.lugu.core.model.Library
import io.github.lightheaded.lugu.core.model.ListControls
import io.github.lightheaded.lugu.core.model.ListFacts
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.BrowseKind
import io.github.lightheaded.lugu.core.sync.LibraryPrefs
import io.github.lightheaded.lugu.core.sync.LibraryRepository
import io.github.lightheaded.lugu.core.sync.ProgressRepository
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

data class BrowseUiState(
    val kind: BrowseKind = BrowseKind.AUTHORS,
    /** Groups after the search box has been applied — exactly what the screen draws. */
    val groups: List<BrowseGroup> = emptyList(),
    /**
     * How many groups the library holds before the search box.
     *
     * Kept so the empty screen can tell the two emptinesses apart: a library that has no
     * series at all needs different words from a search that matched none of them.
     */
    val totalGroups: Int = 0,
    val query: String = "",
    /** What the library holds, so a count can say "12 books" or "12 podcasts" honestly. */
    val mediaType: MediaType = MediaType.BOOK,
)

/**
 * The authors, series or narrators of the library in view.
 *
 * One view model for all three because they differ only in which column the mirror is
 * grouped on; three of these would be the same code with a different query in it, and the
 * screen would still have to choose between them from the same nav argument.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    authRepository: AuthRepository,
    private val libraryRepository: LibraryRepository,
    libraryPrefs: LibraryPrefs,
) : ViewModel() {

    private val kind: BrowseKind = BrowseKind.fromId(savedStateHandle["kind"])

    // Not persisted, unlike the library's own sort and filter: a search is a question
    // about right now, and a box still holding last week's word looks like a broken list.
    private val query = MutableStateFlow("")

    private val account: StateFlow<ActiveAccount?> =
        authRepository.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val selectedLibraryId: Flow<String?> = libraryPrefs.settings.map { it.selectedLibraryId }

    /**
     * Scoped to the library the picker is on, so this list answers the same question the
     * grid does. A null id means nothing has been chosen yet — on that one frame the
     * repository spans every library, which is a fuller answer rather than a wrong one.
     */
    private val groups: Flow<List<BrowseGroup>> =
        combine(account, selectedLibraryId) { current, libraryId -> current to libraryId }
            .flatMapLatest { (current, libraryId) ->
                if (current == null) {
                    flowOf(emptyList())
                } else {
                    libraryRepository.observeGroups(current, kind, libraryId)
                }
            }

    private val selectedLibrary: Flow<Library?> = combine(
        account.flatMapLatest { current ->
            if (current == null) flowOf(emptyList()) else libraryRepository.observeLibraries(current)
        },
        selectedLibraryId,
    ) { libraries, id ->
        libraries.firstOrNull { it.id == id } ?: libraries.firstOrNull()
    }

    val state: StateFlow<BrowseUiState> =
        combine(groups, query, selectedLibrary) { all, text, library ->
            BrowseUiState(
                kind = kind,
                // Sorted here rather than left to the query's alphabetical ordering: a
                // series called "Riverton 10" belongs after "Riverton 2", and SQLite's
                // collation cannot know that.
                groups = all
                    .filter { ListControls.matches(ListFacts(title = it.name), text) }
                    .sortedWith { a, b -> ListControls.naturalCompare(a.name, b.name) },
                totalGroups = all.size,
                query = text,
                mediaType = library?.mediaType ?: MediaType.BOOK,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState(kind = kind))

    fun setQuery(value: String) {
        query.value = value
    }
}

data class BrowseGroupUiState(
    val kind: BrowseKind = BrowseKind.AUTHORS,
    val name: String = "",
    val rows: List<LibraryRow> = emptyList(),
    /** False only until the first emission, so an empty page does not flash "nothing here". */
    val loaded: Boolean = false,
)

/**
 * Everything by one author, in one series, or read by one narrator.
 *
 * The order comes from the repository and is not touched here. An author's books arrive
 * grouped by series and then by sequence, and a series arrives in reading order, both of
 * which are the point of the page — re-sorting them by title on the way to the screen
 * would throw away the only ordering that matters.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowseGroupViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    authRepository: AuthRepository,
    private val libraryRepository: LibraryRepository,
    private val progressRepository: ProgressRepository,
) : ViewModel() {

    private val kind: BrowseKind = BrowseKind.fromId(savedStateHandle["kind"])

    /**
     * The route carries the name as URL-safe base64, not as percent-encoded text.
     *
     * A series called "Who?" or an author with a slash in their name has to survive being
     * a path segment, and percent-encoding leaves the question of who decodes it: Navigation
     * decodes path arguments itself, on some versions, so decoding again here would mangle
     * any name containing a literal percent sign. Base64 has no character Navigation treats
     * as special, so there is exactly one decode and it is this one.
     */
    private val name: String =
        Base64.decode(checkNotNull<String>(savedStateHandle["name"]), Base64.URL_SAFE)
            .toString(Charsets.UTF_8)

    private val account: StateFlow<ActiveAccount?> =
        authRepository.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<BrowseGroupUiState> = account
        .flatMapLatest { current ->
            if (current == null) {
                flowOf(BrowseGroupUiState(kind = kind, name = name))
            } else {
                combine(
                    libraryRepository.observeGroupItems(current, kind, name),
                    progressRepository.observeAll(current),
                ) { items, progress ->
                    val byItem = ItemProgress.byItem(progress)
                    BrowseGroupUiState(
                        kind = kind,
                        name = name,
                        rows = items.map { LibraryRow(it, byItem[it.id]) },
                        loaded = true,
                    )
                }
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            BrowseGroupUiState(kind = kind, name = name),
        )

    /** Plain server URLs; the shared OkHttp interceptor attaches auth for Coil. */
    fun coverUrl(itemId: String, width: Int = 400): String? =
        account.value?.let { "${it.baseUrl}/api/items/$itemId/cover?width=$width" }
}
