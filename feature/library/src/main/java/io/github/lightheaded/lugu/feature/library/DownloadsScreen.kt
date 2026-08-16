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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lightheaded.lugu.core.download.DownloadStatus
import io.github.lightheaded.lugu.core.download.formatBytes
import io.github.lightheaded.lugu.core.model.ItemSort
import io.github.lightheaded.lugu.core.model.ListFilter

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

    Scaffold(
        modifier = modifier,
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
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRowView(
    download: DownloadStatus,
    selectionActive: Boolean,
    isSelected: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            )
            .combinedClickable(
                onClick = { if (selectionActive) onToggle() else onOpen() },
                onLongClick = onToggle,
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
            when {
                download.isComplete -> Text(
                    formatBytes(download.bytesDownloaded),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                download.isFailed -> Text(
                    download.error ?: "Download failed — tap the arrow to try again",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { download.percent.coerceIn(0f, 1f) },
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
