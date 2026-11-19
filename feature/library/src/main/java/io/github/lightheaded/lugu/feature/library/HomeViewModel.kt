package io.github.lightheaded.lugu.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.download.DownloadRepository
import io.github.lightheaded.lugu.core.download.DownloadStatus
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
    /** A finished download of the item is on the phone; the card wears the badge for it. */
    val isDownloaded: Boolean = false,
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
    val row: LibraryRow get() = LibraryRow(entry.item, progress, isDownloaded = isDownloaded)

    /**
     * Whether a tap on this card resumes playback rather than opening the item's page.
     *
     * The judgement is the home tab's — a tap means "carry on" for anything already
     * started and "tell me about this" for anything not — but it lives on the card so the
     * play badge the card wears and the tap the screen handles read one rule. Stated in
     * two places, the badge would eventually promise a play the tap does not do.
     */
    val tapResumes: Boolean get() = progress != null

    private val playedDurationSec: Double
        get() = entry.playedDurationSec.takeIf { it > 0.0 } ?: entry.item.durationSec
}

/**
 * One computed shelf, already paired with whatever progress its entries have.
 *
 * [hasMore] means the queries found more than the row shows. The row is a preview capped
 * at [SHELF_ROW_MAX] — the full answer lives on the Library tab, and a shelf that scrolled
 * sideways through fifty covers was a worse way to read it than the grid already there.
 */
data class ShelfRow(val kind: ShelfKind, val cards: List<ShelfCard>, val hasMore: Boolean = false)

/**
 * How many cards a shelf shows before pointing at the Library tab instead.
 *
 * The queries behind the shelves return up to 50; sideways is a direction for a handful
 * of suggestions, not for reading a list of that size. Anyone who wants the rest gets
 * them as a grid, through the shelf's header or the "See all" tile at the end of the row.
 */
private const val SHELF_ROW_MAX = 10

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
    /**
     * False until the shelves have answered. Before that, empty means unknown, and the
     * screen draws no empty line.
     */
    val loaded: Boolean = false,
)

/**
 * The resume card and the shelves under it, from the shelves in the order they are shown.
 *
 * The continue query is ordered by most recent progress already, so the single most
 * recently played thing is the head of that shelf. Working it out a second time here would
 * be a second definition of "most recent" to disagree with the first. It is read from the
 * arranged list, so hiding "Continue listening" takes the resume card with it.
 *
 * The card is not repeated on the shelf below it: the same book twice, one above the other,
 * reads as a fault. A continue shelf with nothing else on it is not shown at all.
 */
internal fun homeShelves(
    visible: List<Shelf>,
    progress: Map<ProgressKey, MediaProgress>,
    downloaded: Set<String>,
): Pair<ShelfCard?, List<ShelfRow>> {
    fun cardOf(entry: ShelfEntry) = ShelfCard(
        entry,
        progress[ProgressKey(entry.item.id, entry.episodeId)],
        isDownloaded = entry.item.id in downloaded,
    )

    val continueCard = visible.firstOrNull { it.kind == ShelfKind.CONTINUE }
        ?.entries?.firstOrNull()?.let(::cardOf)
    val rows = visible.mapNotNull { shelf ->
        val entries = if (shelf.kind == ShelfKind.CONTINUE) shelf.entries.drop(1) else shelf.entries
        if (shelf.kind == ShelfKind.CONTINUE && entries.isEmpty()) return@mapNotNull null
        ShelfRow(
            shelf.kind,
            entries.take(SHELF_ROW_MAX).map(::cardOf),
            hasMore = entries.size > SHELF_ROW_MAX,
        )
    }
    return continueCard to rows
}

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
    private val downloadRepository: DownloadRepository,
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
    // Null until the repository has answered. An initial value or a missing account is not
    // an answer, and treating it as one drew "Nothing to pick up yet" for the first frame.
    private val shelves: StateFlow<List<Shelf>?> =
        combine(account, settings) { current, prefs -> current to prefs }
            .flatMapLatest { (current, prefs) ->
                if (current == null) {
                    flowOf(null)
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
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val libraryHasArrived: StateFlow<Boolean> = account
        .flatMapLatest { current ->
            if (current == null) flowOf(true) else libraryRepository.observeAnythingMirrored(current)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Items with a finished download, for the downloaded badge on a shelf card. */
    private val downloadedItemIds: StateFlow<Set<String>> = account
        .flatMapLatest { current ->
            if (current == null) flowOf(emptyList()) else downloadRepository.observeAll(current)
        }
        .map(ItemProgress::downloadedItemIds)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val state: StateFlow<HomeUiState> =
        combine(shelves, progressByThing, settings, libraryHasArrived, downloadedItemIds) {
                shelfList, progress, prefs, mirrored, downloaded ->
            // Null until the shelves have answered: no card and no empty line before then.
            if (shelfList == null) return@combine HomeUiState()
            // Hidden shelves never reach here — the repository is told not to query them —
            // so this only puts what is left into the order somebody asked for.
            val visible = prefs.arrangeShelves(shelfList) { it.kind.name }
            val (continueCard, rows) = homeShelves(visible, progress, downloaded)
            HomeUiState(
                continueCard = continueCard,
                shelves = rows,
                shelvesSpanEverything = !prefs.shelvesFollowLibrary,
                libraryHasArrived = mirrored,
                loaded = true,
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

    /**
     * When each item was last listened to: the newest `lastUpdateMs` across all its rows.
     *
     * Not read from [byItem], because that prefers the item-level row, and a podcast can
     * carry one that is older than its newest episode. A book has only the item-level row,
     * so for a book this is that row's time.
     *
     * A finished row counts like any other. "Recently played" means listened to, and the
     * book finished last night is the one most people look for first the next day.
     */
    fun lastPlayedByItem(rows: List<MediaProgress>): Map<String, Long> =
        rows.groupBy { it.libraryItemId }.mapValues { (_, forItem) -> forItem.maxOf { it.lastUpdateMs } }

    /**
     * The items with a finished download on the phone, for the downloaded badge on a card.
     *
     * The same rule as the Library grid: a download still in flight does not yet play
     * without a network, so it does not count.
     */
    fun downloadedItemIds(rows: List<DownloadStatus>): Set<String> =
        rows.filter { it.isComplete }.map { it.libraryItemId }.toSet()
}
