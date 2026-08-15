package io.github.lightheaded.lugu.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.model.MediaProgress
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.LibraryPrefs
import io.github.lightheaded.lugu.core.sync.LibraryRepository
import io.github.lightheaded.lugu.core.sync.LibrarySettings
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import io.github.lightheaded.lugu.core.sync.Shelf
import io.github.lightheaded.lugu.core.sync.ShelfKind
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One computed shelf, already paired with whatever progress its items have. */
data class ShelfRow(val kind: ShelfKind, val rows: List<LibraryRow>)

data class HomeUiState(
    val continueRow: LibraryRow? = null,
    val shelves: List<ShelfRow> = emptyList(),
    val shelvesSpanEverything: Boolean = false,
)

/**
 * What to play now.
 *
 * Split from [LibraryViewModel] because the two screens answer different questions and
 * were fighting over one state object: the shelves answer "what should I play now" and
 * the grid answers "show me everything". Keeping them together is also what let the grid
 * be scoped to the selected library while the shelves above it spanned the account, which
 * read as the library picker being broken.
 *
 * The selected library arrives from the shell rather than being owned here. The picker
 * belongs to the Library tab, and there is no shared store for the selection, so the one
 * composable that hosts both tabs is what carries it across.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    authRepository: AuthRepository,
    libraryRepository: LibraryRepository,
    progressRepository: ProgressRepository,
    libraryPrefs: LibraryPrefs,
) : ViewModel() {

    private val account: StateFlow<ActiveAccount?> =
        authRepository.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val settings: StateFlow<LibrarySettings> = libraryPrefs.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibrarySettings())

    private val progressByItem: StateFlow<Map<String, MediaProgress>> = account
        .flatMapLatest { current ->
            if (current == null) flowOf(emptyList()) else progressRepository.observeAll(current)
        }
        .map(ItemProgress::byItem)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * The library in view comes from the same store the browse tab writes it to, rather
     * than being handed across by the shell. Two owners of one value disagree for a frame,
     * and the visible symptom is shelves that span every library and then snap.
     */
    private val shelves: StateFlow<List<Shelf>> =
        combine(account, settings) { current, prefs -> current to prefs }
            .flatMapLatest { (current, prefs) ->
                if (current == null) {
                    flowOf(emptyList())
                } else {
                    libraryRepository.observeShelves(
                        current,
                        prefs.selectedLibraryId.takeIf { prefs.shelvesFollowLibrary },
                    )
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<HomeUiState> =
        combine(shelves, progressByItem, settings) { shelfList, progress, prefs ->
            val rows = shelfList.map { shelf ->
                ShelfRow(shelf.kind, shelf.items.map { LibraryRow(it, progress[it.id]) })
            }
            HomeUiState(
                // The continue query is ordered by most recent progress already, so the
                // single most recently played item is the head of that shelf. Working it
                // out a second time here would be a second definition of "most recent"
                // to disagree with the first.
                continueRow = rows.firstOrNull { it.kind == ShelfKind.CONTINUE }?.rows?.firstOrNull(),
                shelves = rows,
                shelvesSpanEverything = !prefs.shelvesFollowLibrary,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /**
     * Cover URLs are plain server URLs; the shared OkHttp interceptor attaches auth,
     * so Coil needs nothing special beyond the address.
     */
    fun coverUrl(itemId: String, width: Int = 400): String? =
        account.value?.let { "${it.baseUrl}/api/items/$itemId/cover?width=$width" }
}

/**
 * The progress that describes an item as a whole.
 *
 * Progress is stored per (item, episode) pair, and a podcast has no row at the item
 * level — so reading item-level progress alone found nothing for a podcast and the
 * continue shelf showed it with no position at all, which looks like the shelf is
 * broken rather than like the data being shaped differently.
 *
 * Falling back to the item's most recently updated episode is the correct reading: for a
 * podcast, "continue listening" means the episode you were on, and that row also carries
 * the episode id the resume affordance has to play.
 */
internal object ItemProgress {
    fun byItem(rows: List<MediaProgress>): Map<String, MediaProgress> =
        rows.groupBy { it.libraryItemId }.mapValues { (_, forItem) ->
            forItem.firstOrNull { it.episodeId == null } ?: forItem.maxBy { it.lastUpdateMs }
        }
}
