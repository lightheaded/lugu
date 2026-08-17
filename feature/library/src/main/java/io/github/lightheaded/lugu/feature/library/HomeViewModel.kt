package io.github.lightheaded.lugu.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.model.ContinueLabel
import io.github.lightheaded.lugu.core.model.MediaProgress
import io.github.lightheaded.lugu.core.model.ProgressKey
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.LibraryPrefs
import io.github.lightheaded.lugu.core.sync.LibraryRepository
import io.github.lightheaded.lugu.core.sync.LibrarySettings
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import io.github.lightheaded.lugu.core.sync.Shelf
import io.github.lightheaded.lugu.core.sync.ShelfEntry
import io.github.lightheaded.lugu.core.sync.ShelfKind
import io.github.lightheaded.lugu.core.sync.StartTab
import io.github.lightheaded.lugu.playback.PlaybackConnection
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * One thing on a shelf, paired with the progress of that exact thing.
 *
 * The progress is looked up by item *and* episode rather than by item alone. A podcast
 * with three part-heard episodes is three cards on the continue shelf, and an item-level
 * lookup would give all three the same position — the position of whichever episode was
 * touched last.
 */
data class ShelfCard(
    val entry: ShelfEntry,
    val progress: MediaProgress?,
) {
    val key: String get() = entry.key
    val itemId: String get() = entry.item.id
    val episodeId: String? get() = entry.episodeId

    /**
     * How a part-heard thing names itself, decided by [ContinueLabel] rather than here.
     *
     * The rule is the same one the car draws its Continue rows with, and it only stays the
     * same while there is one copy of it. This card is a caller of that rule, not a second
     * statement of it.
     */
    val title: String get() = ContinueLabel.title(entry.item.title, entry.episodeTitle)

    /** What the title needs placing in, from the same shared rule as [title]. */
    val secondary: String? get() =
        ContinueLabel.subtitle(entry.item.title, entry.item.authorName, entry.episodeTitle)

    val progressFraction: Float get() = progress?.progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f

    /**
     * How much of the thing being played is left, which is the episode's duration for a
     * podcast. Reading the item's own duration instead reports minutes into a feed that
     * is hundreds of hours long.
     */
    val remainingSec: Double get() = playedDurationSec * (1f - progressFraction)

    /** What the grid's card wants, since a shelf card is drawn by the same component. */
    val row: LibraryRow get() = LibraryRow(entry.item, progress)

    private val playedDurationSec: Double
        get() = entry.playedDurationSec.takeIf { it > 0.0 } ?: entry.item.durationSec
}

/** One computed shelf, already paired with whatever progress its entries have. */
data class ShelfRow(val kind: ShelfKind, val cards: List<ShelfCard>)

/**
 * What the player has loaded, as a shelf needs to compare against it.
 *
 * The comparison is on the pair, never on the item alone: a podcast now has several cards
 * on the continue shelf, and matching by item id would light every one of them up whenever
 * any episode of that show was playing.
 */
data class PlayingNow(
    val itemId: String,
    val episodeId: String?,
    val isPlaying: Boolean,
) {
    fun isLoaded(card: ShelfCard): Boolean = itemId == card.itemId && episodeId == card.episodeId
}

data class HomeUiState(
    val continueCard: ShelfCard? = null,
    val shelves: List<ShelfRow> = emptyList(),
    val shelvesSpanEverything: Boolean = false,
    /**
     * Whether anything has been mirrored for this account yet.
     *
     * An empty Home means two different things and used to say only one of them. Right
     * after signing in the library has not arrived, and "the Library tab has everything"
     * sends someone to a second empty screen; once it has arrived, the same emptiness
     * honestly means nothing is part-heard.
     */
    val libraryHasArrived: Boolean = true,
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
 * The selected library is read from `LibraryPrefs`, the same store the browse tab writes
 * it to, rather than being handed across by the shell. One screen owning the value and
 * telling the other leaves a frame where the two disagree, which is visible as shelves
 * that span every library and then snap to one.
 *
 * The playback connection is here because the resume card carries a transport button, and
 * a button that reports playback has to read the same state the player does. Anything else
 * is a second opinion, and the symptom of the second opinion was a Play icon that stayed
 * Play after playback had started.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    authRepository: AuthRepository,
    private val libraryRepository: LibraryRepository,
    progressRepository: ProgressRepository,
    libraryPrefs: LibraryPrefs,
    private val playback: PlaybackConnection,
) : ViewModel() {

    private val account: StateFlow<ActiveAccount?> =
        authRepository.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val settings: StateFlow<LibrarySettings> = libraryPrefs.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibrarySettings())

    /**
     * Which tab the shell opens on, or null while that is still unknown.
     *
     * Null rather than the default is the whole point: the store answers asynchronously, so
     * a non-null placeholder would draw the Home tab for a frame and then throw someone who
     * asked for Library into it, which reads as the preference being ignored and then
     * grudgingly obeyed. One empty frame is quieter than a flip.
     */
    val startTab: StateFlow<StartTab?> = libraryPrefs.settings
        .map { it.startTab }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Progress keyed by the pair it is stored under, rather than by item.
     *
     * Progress rows are per (item, episode) already, and a shelf entry now names its
     * episode, so the two line up exactly. The old item-level map had to guess which
     * episode a podcast meant, and could only ever return one answer for a show with
     * three episodes on the go.
     */
    private val progressByThing: StateFlow<Map<ProgressKey, MediaProgress>> = account
        .flatMapLatest { current ->
            if (current == null) flowOf(emptyList()) else progressRepository.observeAll(current)
        }
        .map { rows -> rows.associateBy { it.key } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * What the player has loaded, and whether it is running.
     *
     * `isPlaying` is taken from the connection untouched. It is deliberately optimistic
     * while a start is still resolving, so that a press flips the button at once; adding a
     * second layer of optimism here would only make the button disagree with the player
     * for longer when a start fails.
     */
    val playingNow: StateFlow<PlayingNow?> =
        combine(playback.nowPlaying, playback.state) { loaded, player ->
            loaded?.let { PlayingNow(it.libraryItemId, it.episodeId, player.isPlaying) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
                        account = current,
                        libraryId = prefs.selectedLibraryId.takeIf { prefs.shelvesFollowLibrary },
                        // A shelf switched off is not queried at all. Some of these are the
                        // heaviest queries in the app, and running one to throw the answer
                        // away is a cost paid on every library change by someone who said
                        // they did not want it.
                        hidden = ShelfKind.entries.filter { it.name in prefs.hiddenShelves }.toSet(),
                    )
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val libraryHasArrived: StateFlow<Boolean> = account
        .flatMapLatest { current ->
            if (current == null) flowOf(true) else libraryRepository.observeAnythingMirrored(current)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val state: StateFlow<HomeUiState> =
        combine(shelves, progressByThing, settings, libraryHasArrived) { shelfList, progress, prefs, mirrored ->
            // Hidden shelves never reach here — the repository is told not to query them —
            // so this only puts what is left into the order somebody asked for.
            val visible = prefs.arrangeShelves(shelfList) { it.kind.name }
            val rows = visible.map { shelf ->
                ShelfRow(
                    shelf.kind,
                    shelf.entries.map { entry ->
                        ShelfCard(entry, progress[ProgressKey(entry.item.id, entry.episodeId)])
                    },
                )
            }
            HomeUiState(
                // The continue query is ordered by most recent progress already, so the
                // single most recently played thing is the head of that shelf. Working it
                // out a second time here would be a second definition of "most recent"
                // to disagree with the first.
                //
                // Read from the arranged list, so hiding "Continue listening" takes the
                // resume card with it. Leaving a card of that name on a screen where the
                // shelf was explicitly turned off would look like the setting failed.
                continueCard = rows.firstOrNull { it.kind == ShelfKind.CONTINUE }?.cards?.firstOrNull(),
                shelves = rows,
                shelvesSpanEverything = !prefs.shelvesFollowLibrary,
                libraryHasArrived = mirrored,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /**
     * Cover URLs are plain server URLs; the shared OkHttp interceptor attaches auth,
     * so Coil needs nothing special beyond the address.
     */
    fun coverUrl(itemId: String, width: Int = 400): String? =
        account.value?.let { "${it.baseUrl}/api/items/$itemId/cover?width=$width" }

    /**
     * Only ever called for the card that is already loaded in the player, so this is a
     * transport command rather than a request to start something. Starting a fresh play
     * for the thing already playing would drop the position it is at and buffer again.
     */
    fun togglePlayPause() = playback.togglePlayPause()
}

/**
 * The progress that describes an item as a whole.
 *
 * Progress is stored per (item, episode) pair, and a podcast has no row at the item
 * level — so reading item-level progress alone found nothing for a podcast and a card
 * showed it with no position at all, which looks broken rather than like the data being
 * shaped differently. Falling back to the item's most recently updated episode is the
 * best available reading where a podcast has to be shown as one card.
 *
 * The shelves no longer use this. A shelf entry names its own episode now, so
 * [HomeViewModel] looks progress up by the pair and a show with three episodes on the go
 * gets three cards with three positions. What is left here is the author, series,
 * narrator and collection grids, where one item is one card by construction and the most
 * recent episode is the only sensible thing to draw.
 */
internal object ItemProgress {
    fun byItem(rows: List<MediaProgress>): Map<String, MediaProgress> =
        rows.groupBy { it.libraryItemId }.mapValues { (_, forItem) ->
            forItem.firstOrNull { it.episodeId == null } ?: forItem.maxBy { it.lastUpdateMs }
        }
}
