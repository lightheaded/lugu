package io.github.lightheaded.lugu.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.download.DownloadRepository
import io.github.lightheaded.lugu.core.download.DownloadStatus
import io.github.lightheaded.lugu.core.model.ItemSort
import io.github.lightheaded.lugu.core.model.ListControls
import io.github.lightheaded.lugu.core.model.ListFacts
import io.github.lightheaded.lugu.core.model.ListFilter
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.DownloadPrefs
import io.github.lightheaded.lugu.core.sync.DownloadSettings
import io.github.lightheaded.lugu.core.sync.LibraryPrefs
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

/** An item and an episode together name a download; the item alone does not. */
internal val DownloadStatus.rowKey: String get() = "$libraryItemId#${episodeId.orEmpty()}"

/**
 * What one row says about its own download, decided here rather than in the drawing.
 *
 * The three states are exclusive and the row draws exactly one of them, in one line of
 * space that the row keeps in every state. See `DownloadRowView` for the layout, and for
 * why the two mechanisms of the `StatusStrip` rule do not fit a list row.
 *
 * Kept apart from the drawing because both decisions are testable this way: which state a
 * row is in, and how much of a long failure a single line carries.
 */
internal sealed interface RowStatus {

    /** The download is complete. The row reports what it costs on the phone. */
    data class Size(val bytes: Long) : RowStatus

    /** The download runs, or it waits its turn. The row reports how far it got. */
    data class Progress(val fraction: Float) : RowStatus

    /**
     * The download failed.
     *
     * @param line what the row shows: one line, cut to fit beside the retry button.
     * @param full the whole message, with all white space collapsed to single spaces.
     * @param hasMore true if [line] holds less than [full]. The row is tappable only then,
     *   because a tap that repeats a line already on screen reads as a broken tap.
     */
    data class Failure(val line: String, val full: String, val hasMore: Boolean) : RowStatus
}

/**
 * The word for this state is "failed", because the rest of the app already uses it.
 *
 * `DownloadRefusalTest` in `:core:download` asserts that a refusal never says "failed", on
 * the grounds that this screen owns the word for a download that broke part way through.
 */
private const val FAILED_WITH_NO_REASON = "Failed. Tap the arrow to try again."

/**
 * About as many characters of `labelSmall` as a phone row holds beside the retry button.
 *
 * The cut is made here, in text, and not left to `TextOverflow.Ellipsis` alone. A screen
 * reader reads the whole string of a text, and not the part the eye can see, so a line that
 * only looks short still reads out four sentences. The ellipsis stays as the safety net for
 * a large font scale.
 */
private const val FAILURE_LINE_CHARS = 48

/** A full stop, a question mark or an exclamation mark that ends a sentence. */
private val SENTENCE_END = Regex("""[.!?](\s|$)""")

private val WHITE_SPACE = Regex("""\s+""")

/** Which of the three things a row draws, and with which words. */
internal fun rowStatusOf(download: DownloadStatus): RowStatus = when {
    // A complete download is complete even if the server reported an error on the way.
    download.isComplete -> RowStatus.Size(download.bytesDownloaded)
    download.isFailed -> failureOf(download.error)
    else -> RowStatus.Progress(download.percent.coerceIn(0f, 1f))
}

/**
 * How much of the server's words one row carries.
 *
 * The row takes the first sentence, because the first sentence names the failure and the
 * later ones qualify it. The storage-cap message is the example to hold in mind: "Stopped:
 * downloads have reached the 8 GB cap, with 7.6 GB used." is the part that identifies the
 * problem, and three more sentences name the fix. The fix must not be lost, so the whole
 * message goes to the screen's message channel, which a tap on the row opens.
 *
 * A message with no reason in it gets [FAILED_WITH_NO_REASON], which names the failure and
 * the retry together. Media3 gives an exception message, and an exception message can be
 * absent.
 */
internal fun failureOf(error: String?): RowStatus.Failure {
    val full = error?.replace(WHITE_SPACE, " ")?.trim().orEmpty()
    if (full.isEmpty()) {
        return RowStatus.Failure(FAILED_WITH_NO_REASON, FAILED_WITH_NO_REASON, hasMore = false)
    }
    val line = shortenFailure(full)
    return RowStatus.Failure(line = line, full = full, hasMore = line != full)
}

/**
 * The first sentence, and no more of it than one line holds.
 *
 * The ellipsis at the end is the only mark that says more text exists, so it is added
 * whenever anything was cut — a dropped second sentence counts as a cut. A cut lands on a
 * word boundary if the line has one after its half-way point. A long address has no space
 * in it, and a hard cut of one is better than three characters of it.
 */
internal fun shortenFailure(message: String): String {
    val end = SENTENCE_END.find(message)
    val sentence = if (end == null) message else message.take(end.range.first + 1)
    if (sentence == message && sentence.length <= FAILURE_LINE_CHARS) return sentence
    val head = sentence.take(FAILURE_LINE_CHARS)
    val lastSpace = head.lastIndexOf(' ')
    val cut = if (head.length < sentence.length && lastSpace > FAILURE_LINE_CHARS / 2) {
        head.take(lastSpace)
    } else {
        head
    }
    return cut.trimEnd { !it.isLetterOrDigit() } + "…"
}

data class DownloadsUiState(
    val downloads: List<DownloadStatus> = emptyList(),
    /** After the search, the filter and the sort — what the list draws. */
    val visible: List<DownloadStatus> = emptyList(),
    val bytesUsed: Long = 0,
    /**
     * Bytes held by audio that was streamed rather than downloaded.
     *
     * Shown apart from [bytesUsed] and never added to it. A download was asked for and is
     * never evicted; retained streamed audio is disposable and drops oldest-first at its
     * own bound. Adding them would put a figure beside the cap that the cap does not
     * govern.
     */
    val retainedStreamBytes: Long = 0,
    val settings: DownloadSettings = DownloadSettings(),
    val query: String = "",
    val sort: ItemSort = ItemSort.ADDED,
    val filter: ListFilter = ListFilter.ALL,
    val selectionActive: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    /** Why something this screen was asked to do did not happen. */
    val message: String? = null,
) {
    val active: List<DownloadStatus> get() = downloads.filter { it.isActive }
    val complete: List<DownloadStatus> get() = downloads.filter { it.isComplete }
    val failed: List<DownloadStatus> get() = downloads.filter { it.isFailed }

    val fractionOfCap: Float
        get() = if (settings.storageCapBytes > 0) {
            (bytesUsed.toDouble() / settings.storageCapBytes).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val downloadRepository: DownloadRepository,
    private val libraryPrefs: LibraryPrefs,
    downloadPrefs: DownloadPrefs,
) : ViewModel() {

    private val query = MutableStateFlow("")

    // Remembered between visits, under this screen's own two keys. Borrowing the grid's
    // would have tied two unrelated screens together — sorting downloads by size would
    // re-order the library — which is why they were held in memory at first, and holding
    // them in memory meant re-picking the ordering on every visit instead.
    //
    // The search box is deliberately *not* remembered. An ordering is a decision about how
    // a list is read; a search is a thing being looked for, and returning to a screen
    // showing three of forty downloads with a stale word in the box reads as lost data.
    //
    // Read as one pair rather than as two flows because `combine` tops out at five, and
    // they arrive together from one store anyway.
    private val listPrefs = libraryPrefs.settings.map { it.downloadSort to it.downloadFilter }

    private val message = MutableStateFlow<String?>(null)

    private val selection = MutableStateFlow(Selection())

    private val account: StateFlow<ActiveAccount?> =
        authRepository.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val content: Flow<DownloadsUiState> = account
        .flatMapLatest { current ->
            if (current == null) {
                flowOf(DownloadsUiState())
            } else {
                combine(
                    downloadRepository.observeAll(current),
                    downloadRepository.observeBytesUsed(current),
                    downloadPrefs.settings,
                ) { downloads, bytes, settings ->
                    DownloadsUiState(downloads = downloads, bytesUsed = bytes, settings = settings)
                }.map { it.copy(retainedStreamBytes = downloadRepository.retainedStreamBytes()) }
            }
        }

    val state: StateFlow<DownloadsUiState> =
        combine(content, query, listPrefs, message, selection) { base, search, prefs, note, picked ->
            val (order, chosenFilter) = prefs
            val facts = base.downloads.factsByKey()
            val visible = ListControls.sortItems(
                base.downloads.filter {
                    val row = facts.getValue(it.rowKey)
                    ListControls.matches(row, chosenFilter) && ListControls.matches(row, search)
                },
                order,
            ) { facts.getValue(it.rowKey) }
            val onScreen = picked.retaining(visible.map { it.rowKey })
            base.copy(
                visible = visible,
                query = search,
                sort = order,
                filter = chosenFilter,
                selectionActive = onScreen.active,
                selectedIds = onScreen.ids,
                message = note,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun setSort(value: ItemSort) {
        viewModelScope.launch { libraryPrefs.setDownloadSort(value) }
    }

    fun setFilter(value: ListFilter) {
        viewModelScope.launch { libraryPrefs.setDownloadFilter(value) }
    }

    fun toggleSelection(key: String) {
        selection.value = selection.value.toggle(key)
    }

    fun selectAllVisible() {
        selection.value = selection.value.selectAll(state.value.visible.map { it.rowKey })
    }

    fun clearSelection() {
        selection.value = selection.value.cleared()
    }

    fun remove(status: DownloadStatus) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            downloadRepository.remove(current, status.libraryItemId, status.episodeId)
        }
    }

    /**
     * Clearing space is the reason anyone opens this screen, and it is rarely one book.
     *
     * No confirmation: the rows say how much each is worth in bytes, the selection had to
     * be made deliberately, and anything deleted can be downloaded again.
     */
    fun removeSelected() {
        val chosen = state.value.visible.filter { it.rowKey in state.value.selectedIds }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            chosen.forEach { downloadRepository.remove(current, it.libraryItemId, it.episodeId) }
            clearSelection()
        }
    }

    /**
     * Starts a failed download again, and says so when it will not start.
     *
     * The refusal was thrown away here, which made the commonest failure on this screen
     * invisible: a download stopped by the storage cap keeps the ordinary retry button, and
     * pressing it hits the same cap and is refused before anything is enqueued. Nothing
     * changed on screen, so the button read as broken rather than as the cap holding. The
     * refusal states its own arithmetic — "Needs 56 MB, and 7.6 GB of the 8 GB cap is
     * already used" — which is the sentence that says what to change first.
     */
    fun retry(status: DownloadStatus) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            downloadRepository.download(current, status.libraryItemId, status.episodeId)
                .onFailure { failure ->
                    message.value = failure.message ?: "That download could not be started again"
                }
        }
    }

    /**
     * Puts the whole of a failure where a whole message fits.
     *
     * A row holds one line, so the rest of the words need a home. They go to the channel
     * this screen already uses for the outcome of a tap, which is the same channel a
     * refused retry uses. That keeps one place on this screen for long words, and it keeps
     * the row the same height in every state.
     */
    fun explain(status: DownloadStatus) {
        val failure = rowStatusOf(status) as? RowStatus.Failure ?: return
        message.value = failure.full
    }

    fun dismissMessage() {
        message.value = null
    }

    /**
     * Downloads described in the shared vocabulary.
     *
     * Recency goes in as a rank rather than a timestamp, because a [DownloadStatus] does
     * not carry one — the repository already returns rows newest first, so position in
     * that list is the only ordering information there is. Size is a real field, so it
     * needs no such trick.
     */
    private fun List<DownloadStatus>.factsByKey(): Map<String, ListFacts> =
        withIndex().associate { (index, status) ->
            status.rowKey to ListFacts(
                title = status.title,
                secondary = status.author,
                addedAtMs = (size - index).toLong(),
                sizeBytes = maxOf(status.bytesTotal, status.bytesDownloaded),
                // A finished download is not "in progress", whatever its percentage says.
                progressFraction = if (status.isComplete) 0f else status.percent.coerceIn(0f, 1f),
                isDownloaded = status.isComplete,
            )
        }
}
