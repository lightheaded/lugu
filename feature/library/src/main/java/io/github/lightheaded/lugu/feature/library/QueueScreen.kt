package io.github.lightheaded.lugu.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lightheaded.lugu.core.model.formatLengthCompact
import io.github.lightheaded.lugu.core.sync.QueueItem

/**
 * What plays after this.
 *
 * The queue is the one list in the app the listener composes themselves, so it is the
 * one list that must not reorder itself: rows sit exactly where they were dropped, and
 * nothing is added or removed except by an explicit action or by playing through.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    onBack: () -> Unit,
    onPlay: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QueueViewModel = hiltViewModel(),
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
                    onSelectAll = viewModel::selectAll,
                    actions = listOf(
                        SelectionAction(
                            "Remove from queue",
                            Icons.Default.Delete,
                            viewModel::removeSelected,
                            state.selectedIds.isNotEmpty(),
                        ),
                    ),
                )
            } else {
                TopAppBar(
                    title = { Text("Up next") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (state.items.isNotEmpty()) {
                            IconButton(onClick = viewModel::enterSelection) {
                                Icon(Icons.Default.Checklist, contentDescription = "Select entries")
                            }
                            TextButton(onClick = viewModel::clear) { Text("Clear") }
                        }
                    },
                )
            }
        },
    ) { padding ->
        if (state.items.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Nothing queued.\n\nAdd a book or an episode from its page and it plays when " +
                        "this one finishes. With the queue empty, lugu carries on with the next " +
                        "book in the series or the next episode instead — both can be turned off " +
                        "in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        ReorderableQueue(
            items = state.items,
            modifier = Modifier.fillMaxSize().padding(padding),
            onPlay = onPlay,
            onRemove = viewModel::remove,
            onMove = viewModel::move,
            selectionActive = state.selectionActive,
            selectedIds = state.selectedIds,
            onToggleSelection = viewModel::toggleSelection,
        )
    }
}

/**
 * Long-press and drag to reorder.
 *
 * The reordering is done locally while the finger is down and committed once on release,
 * so a drag across five rows is one database write rather than five, and the list cannot
 * fight the drag by re-emitting underneath it.
 *
 * In selection mode the drag detector is not installed at all, rather than installed and
 * ignoring its callbacks: two long-press handlers competing for the same press is how a
 * list ends up doing neither thing reliably.
 */
@Composable
private fun ReorderableQueue(
    items: List<QueueItem>,
    selectionActive: Boolean,
    selectedIds: Set<String>,
    onPlay: (String, String?) -> Unit,
    onRemove: (QueueItem) -> Unit,
    onMove: (Int, Int) -> Unit,
    onToggleSelection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Keyed on the queue itself, so an external change (playing through, a removal)
    // replaces the working copy — but never mid-drag, since nothing writes until release.
    var order by remember(items) { mutableStateOf(items) }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragTo by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    fun reset() {
        dragFrom = null
        dragTo = null
        dragOffset = 0f
    }

    val dragging = if (selectionActive) {
        Modifier
    } else {
        Modifier.pointerInput(items) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                        offset.y.toInt() in it.offset..(it.offset + it.size)
                    }
                    dragFrom = hit?.index
                    dragTo = hit?.index
                    dragOffset = 0f
                },
                onDrag = { change, amount ->
                    change.consume()
                    val current = dragTo ?: return@detectDragGesturesAfterLongPress
                    dragOffset += amount.y

                    val dragged = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }
                        ?: return@detectDragGesturesAfterLongPress
                    val centre = dragged.offset + dragged.size / 2 + dragOffset
                    val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { candidate ->
                        candidate.index != current && centre.toInt() in candidate.offset..(candidate.offset + candidate.size)
                    } ?: return@detectDragGesturesAfterLongPress

                    order = order.toMutableList().apply { add(target.index, removeAt(current)) }
                    // Keep the row under the finger after the list reflows around it.
                    dragOffset -= (target.offset - dragged.offset)
                    dragTo = target.index
                },
                onDragEnd = {
                    val from = dragFrom
                    val to = dragTo
                    if (from != null && to != null && from != to) onMove(from, to)
                    reset()
                },
                onDragCancel = {
                    order = items
                    reset()
                },
            )
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 32.dp),
        modifier = modifier.then(dragging),
    ) {
        itemsIndexed(order, key = { _, it -> it.rowKey }) { index, item ->
            val isDragging = index == dragTo
            QueueRowView(
                item = item,
                position = index + 1,
                onPlay = {
                    if (selectionActive) onToggleSelection(item.rowKey) else onPlay(item.libraryItemId, item.episodeId)
                },
                onRemove = { onRemove(item) },
                modifier = Modifier
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f },
                isDragging = isDragging,
                selectionActive = selectionActive,
                isSelected = item.rowKey in selectedIds,
            )
        }
    }
}

@Composable
private fun QueueRowView(
    item: QueueItem,
    position: Int,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    isDragging: Boolean = false,
    selectionActive: Boolean = false,
    isSelected: Boolean = false,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = if (isDragging) 6.dp else 0.dp,
        color = when {
            isDragging -> MaterialTheme.colorScheme.surfaceVariant
            isSelected -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surface
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPlay)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The position gives way to the tick: both are the row's left-hand marker, and
            // while several rows are being picked out the ordinal is not what is being read.
            if (selectionActive) {
                Checkbox(checked = isSelected, onCheckedChange = null)
            } else {
                Text(
                    "$position",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    item.title.ifBlank { "Not in this library any more" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val detail = buildString {
                        item.author?.takeIf { it.isNotBlank() }?.let { append(it).append(" · ") }
                        // A queued entry the library no longer holds has no duration to
                        // print, and the dash says so without pretending it is a length.
                        append(item.durationSec.takeIf { it > 0 }?.let(::formatLengthCompact) ?: "—")
                        if (item.isSuggestion) append(" · suggested")
                    }
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (item.isDownloaded) {
                        Icon(
                            Icons.Default.DownloadDone,
                            contentDescription = "Downloaded",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Both trailing controls are about one row, and neither means anything while
            // the bar above is acting on several.
            if (!selectionActive) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Close, contentDescription = "Remove from queue")
                }
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = "Long-press and drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}
