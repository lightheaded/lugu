package io.github.lightheaded.lugu.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lightheaded.lugu.core.download.DownloadStatus
import io.github.lightheaded.lugu.core.download.formatBytes
import io.github.lightheaded.lugu.core.model.ItemSort
import io.github.lightheaded.lugu.core.model.ListFilter
import io.github.lightheaded.lugu.core.ui.reservedSpace
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Long enough to read a refusal that quotes two byte figures, and no longer.
 *
 * Deliberately longer than the grid's batch note: that one confirms something that
 * happened, this one explains why nothing did. It is now the floor rather than the whole
 * rule, because this channel also carries a whole download failure, and the storage-cap
 * message is four sentences long.
 */
private const val SHORTEST_READ_MS = 8_000L

/** Nobody reads for half a minute, and the note has a dismiss button of its own. */
private const val LONGEST_READ_MS = 24_000L

/** Slower than a reader of the language, because a reader of a second language reads it. */
private const val PER_WORD_MS = 500L

/**
 * How long one message stays on screen.
 *
 * A fixed time fits one length of message. This channel now carries two: a refusal of two
 * lines, and the whole reason a download failed, which the server writes and which can run
 * to four sentences. A note cut off in the middle is the fault this replaces.
 */
internal fun readingTimeMs(message: String): Long {
    val words = message.split(' ', '\n', '\t').count { it.isNotBlank() }
    return (words * PER_WORD_MS).coerceIn(SHORTEST_READ_MS, LONGEST_READ_MS)
}

/**
 * What is on the phone, and how much room it is taking.
 *
 * The storage readout is at the top rather than buried in settings because the cap is
 * the thing that will eventually refuse a download, and someone who hits it should be
 * able to see why and fix it in the same place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler(enabled = state.selectionActive) { viewModel.clearSelection() }

    val snackbarHostState = remember { SnackbarHostState() }

    // A snackbar rather than a line in the list. The refusal explains a tap that has just
    // happened, and a tap can happen on the fortieth row — where a note pinned above the
    // rows is off-screen, which is indistinguishable from the button doing nothing, which
    // is the complaint being fixed. `Indefinite` plus an explicit timeout because Material
    // offers four and ten seconds and this one quotes two byte figures.
    //
    // It carries a second thing now: the whole reason a download failed, which a tap on a
    // failed row asks for. One channel for the long words of this screen, and it takes no
    // layout space, so no row moves when it appears.
    LaunchedEffect(state.message) {
        state.message?.let { note ->
            withTimeoutOrNull(readingTimeMs(note)) {
                snackbarHostState.showSnackbar(
                    message = note,
                    withDismissAction = true,
                    duration = SnackbarDuration.Indefinite,
                )
            }
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (state.selectionActive) {
                SelectionBar(
                    selectedCount = state.selectedIds.size,
                    onClear = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAllVisible,
                    actions = listOf(
                        SelectionAction(
                            "Delete",
                            Icons.Default.Delete,
                            viewModel::removeSelected,
                            state.selectedIds.isNotEmpty(),
                        ),
                    ),
                )
            } else {
                TopAppBar(
                    title = { Text("Downloads") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            }
        },
    ) { padding ->
        if (state.downloads.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Nothing downloaded yet.\n\nDownload a book from its page and it will play " +
                        "with no connection at all.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                    Text(
                        "${formatBytes(state.bytesUsed)} of ${formatBytes(state.settings.storageCapBytes)}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.fractionOfCap },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${state.complete.size} ready to play offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // On its own line, below the bar, and never inside the cap figure.
                    // This is audio kept from streaming: it was not asked for, it does not
                    // count against the allowance, and it is dropped oldest-first on its
                    // own bound. Putting it inside the cap total would be a number beside
                    // the allowance that the allowance does not govern.
                    if (state.retainedStreamBytes > 0) {
                        Text(
                            "${formatBytes(state.retainedStreamBytes)} kept from streaming, " +
                                "outside the cap",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
                ListControlsBar(
                    query = state.query,
                    onQueryChange = viewModel::setQuery,
                    searchPlaceholder = "Search downloads",
                    sortOptions = DOWNLOAD_SORTS,
                    selectedSortId = state.sort.id,
                    onSortSelected = { viewModel.setSort(ItemSort.fromId(it)) },
                    filters = DOWNLOAD_FILTERS,
                    selectedFilter = state.filter,
                    onFilterSelected = viewModel::setFilter,
                    // On this screen the question is about bytes, not about listening.
                    labelFor = { filter ->
                        if (filter == ListFilter.IN_PROGRESS) "Downloading" else filter.label
                    },
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            if (state.visible.isEmpty()) {
                item {
                    Text(
                        "Nothing here matches that.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
            }

            items(state.visible, key = { it.rowKey }) { download ->
                DownloadRowView(
                    download = download,
                    selectionActive = state.selectionActive,
                    isSelected = download.rowKey in state.selectedIds,
                    onOpen = { onOpenItem(download.libraryItemId) },
                    onToggle = { viewModel.toggleSelection(download.rowKey) },
                    onRemove = { viewModel.remove(download) },
                    onRetry = { viewModel.retry(download) },
                    onExplain = { viewModel.explain(download) },
                )
            }
        }
    }
}

/**
 * A downloaded thing, and the space it is costing.
 *
 * Long-press opens selection, matching the episode list: the two lists are deleted from
 * in the same way and should not be learned twice.
 *
 * ## Why the row keeps one line of space it does not always fill
 *
 * The `StatusStrip` rule holds here — a message may appear, and nothing else may move —
 * but neither of its two mechanisms fits a list row.
 *
 * An overlay under the top bar cannot say which of forty rows failed, and the row that
 * failed can be off screen while the overlay is not. Reserved space beside an input is the
 * other mechanism, and a row is not an input: it has no field to sit under, and forty rows
 * would reserve forty empty lines.
 *
 * The third form keeps the principle of both. The row already draws one line about the
 * download in every state, so that line becomes a slot of exactly one line, held in every
 * state. A complete download reports its bytes there. A download in flight draws its bar
 * there. A failure writes one line of words there. Nothing is added and nothing is
 * removed, so no row below moves, and the row a finger reaches for stays where the eye
 * found it.
 *
 * The line is one line of `labelSmall` and not a count of dp, so it grows with the font
 * scale and stays equal across the three states at every scale.
 *
 * The words are cut to fit by `shortenFailure`, and the whole message goes to the screen's
 * message channel, which a tap on the row opens. `failureOf` holds the reasons for both.
 */
@OptIn(ExperimentalFoundationApi::class)
/**
 * One row of the downloads list.
 *
 * `internal` rather than private so that [DownloadRowHeightTest] can measure it. The row
 * promises that a failure changes no height, and only a measurement of the real row proves
 * that promise.
 */
@Composable
internal fun DownloadRowView(
    download: DownloadStatus,
    selectionActive: Boolean,
    isSelected: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    onExplain: () -> Unit,
) {
    val status = rowStatusOf(download)
    val failure = status as? RowStatus.Failure
    // A tap on a failed row asks for the words the line could not hold, and only if the
    // line dropped any. If it dropped none, the tap keeps its usual job and opens the item.
    // The cost is the item page, which this one row no longer opens. It is paid because the
    // next step after a failure is the retry button beside the words, and because every
    // other list in the app still opens the item.
    val explains = !selectionActive && failure?.hasMore == true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            )
            .combinedClickable(
                onClick = {
                    when {
                        selectionActive -> onToggle()
                        explains -> onExplain()
                        else -> onOpen()
                    }
                },
                onClickLabel = if (explains) "Read why the download failed" else null,
                onLongClick = onToggle,
            )
            // A failure must name the item, because "Failed" alone names nothing in a list
            // of forty. The row is one node to a screen reader — the title, the author and
            // the line about the download read as one — so the row is the live region and
            // the line inside it is not. It is polite, so a reader in the middle of a
            // title finishes the title first.
            .then(
                if (failure != null) {
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selectionActive) {
            Checkbox(checked = isSelected, onCheckedChange = null)
        }
        Column(Modifier.weight(1f)) {
            Text(
                download.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            download.author?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // One line about the download, and one line of space for it in every state.
            // The reasons are in this function's doc comment.
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart,
            ) {
                // The space itself: one line of the style that fills it, drawn as nothing
                // and hidden from a screen reader, so that kept space is never read out as
                // an empty line.
                Text(
                    " ",
                    style = MaterialTheme.typography.labelSmall,
                    minLines = 1,
                    maxLines = 1,
                    modifier = Modifier.reservedSpace(visible = false),
                )
                when (status) {
                    is RowStatus.Size -> Text(
                        formatBytes(status.bytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )

                    is RowStatus.Failure -> Text(
                        status.line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        // The words were already cut to a character count. This is the net
                        // for a font scale that no character count can predict.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    is RowStatus.Progress -> LinearProgressIndicator(
                        progress = { status.fraction },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                    )
                }
            }
        }
        // One row's own control has no place beside a bar acting on several.
        if (!selectionActive) {
            DownloadButton(
                download = download,
                onDownload = onRetry,
                onRemove = onRemove,
                compact = true,
            )
        }
    }
}

/**
 * Three orderings, because the questions asked of a downloads list are which one is this,
 * what is taking the space, and what did I fetch last.
 */
private val DOWNLOAD_SORTS = listOf(
    SortOption(ItemSort.TITLE.id, "Title"),
    SortOption(ItemSort.SIZE.id, ItemSort.SIZE.label),
    SortOption(ItemSort.ADDED.id, "Recently added"),
)

/**
 * The listening filters do not apply here — nothing on this screen knows how far through
 * a book anyone is — so the set is cut to the two states a download can be in.
 */
private val DOWNLOAD_FILTERS = listOf(ListFilter.ALL, ListFilter.IN_PROGRESS, ListFilter.DOWNLOADED)
