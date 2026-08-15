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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.lightheaded.lugu.core.download.DownloadStatus
import io.github.lightheaded.lugu.core.model.EpisodeSort
import io.github.lightheaded.lugu.core.model.ListFilter
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.model.PodcastEpisode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(
    onBack: () -> Unit,
    onPlay: (itemId: String, episodeId: String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ItemDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val item = state.item
    val snackbarHostState = remember { SnackbarHostState() }

    // A refusal is shown as an overlay, not as inline content: making the page jump to
    // report a failed button press is worse than the failure.
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissMessage()
    }

    // Getting out of selection mode is the commonest thing to want, and back is where
    // people reach for it first — before they find the cross on the bar.
    BackHandler(enabled = state.selectionActive) { viewModel.clearSelection() }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (state.selectionActive) {
                EpisodeSelectionBar(state = state, viewModel = viewModel)
            } else {
                TopAppBar(
                    title = { Text(item?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            }
        },
    ) { padding ->
        if (item == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }

        // No paging here, deliberately, and none is needed: a LazyColumn composes only
        // the rows on screen, and the whole feed is already in memory from Room. Paging
        // a list that is neither fetched nor composed in pages would add a loading state
        // to a screen that has no reason to have one.
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AsyncImage(
                        model = state.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(140.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Column {
                        Text(item.title, style = MaterialTheme.typography.titleLarge)
                        item.authorName?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        item.narratorName?.let {
                            Text(
                                "Read by $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            formatDuration(item.durationSec),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (state.progressFraction > 0f) {
                item {
                    Column {
                        LinearProgressIndicator(
                            progress = { state.progressFraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${formatDuration(state.positionSec)} of ${formatDuration(item.durationSec)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (item.mediaType == MediaType.BOOK) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onPlay(item.id, null) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(if (state.progressFraction > 0f) "Resume" else "Play")
                        }
                        DownloadButton(
                            download = state.download,
                            onDownload = { viewModel.download() },
                            onRemove = { viewModel.removeDownload() },
                        )
                        QueueMenu(
                            onPlayNext = { viewModel.playNext() },
                            onAddToQueue = { viewModel.addToQueue() },
                        )
                    }
                }
            }

            item.description?.takeIf { it.isNotBlank() }?.let { description ->
                item {
                    Text(description, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (state.episodeCount > 0) {
                item {
                    Column {
                        Text("Episodes", style = MaterialTheme.typography.titleMedium)
                        ListControlsBar(
                            query = state.query,
                            onQueryChange = viewModel::setQuery,
                            searchPlaceholder = "Search episodes",
                            sortOptions = EpisodeSort.entries.map { SortOption(it.id, it.label) },
                            selectedSortId = state.episodeSort.id,
                            onSortSelected = { viewModel.setSort(EpisodeSort.fromId(it)) },
                            filters = ListFilter.entries,
                            selectedFilter = state.episodeFilter,
                            onFilterSelected = viewModel::setFilter,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        // The count is the receipt for the filter: without it, a chip that
                        // removes nine hundred rows and a chip that removes none look the same.
                        Text(
                            episodeCountLine(state.episodes.size, state.episodeCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            if (state.episodeCount > 0 && state.episodes.isEmpty()) {
                item {
                    Text(
                        "No episodes match that.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(state.episodes, key = { it.episode.id }) { row ->
                EpisodeRowView(
                    row = row,
                    selectionActive = state.selectionActive,
                    isSelected = row.episode.id in state.selectedIds,
                    // Playing is the whole gesture: one call, and no navigation of its own.
                    // The caller starts playback and opens the player, and the transport's
                    // optimistic Play/Pause state is settled in PlaybackConnection, so
                    // there is nothing for this row to confirm afterwards.
                    onPlay = { onPlay(item.id, row.episode.id) },
                    onToggle = { viewModel.toggleSelection(row.episode.id) },
                    onDownload = { viewModel.download(row.episode.id) },
                    onRemoveDownload = { viewModel.removeDownload(row.episode.id) },
                    onPlayNext = { viewModel.playNext(row.episode.id) },
                    onAddToQueue = { viewModel.addToQueue(row.episode.id) },
                )
            }
        }
    }
}

/**
 * The actions worth offering on a handful of episodes at once.
 *
 * Remove download is present but disabled unless something in the selection is actually
 * on the phone: hiding it instead would make the bar's contents shift about between
 * selections, and a control that moves is harder to aim at than one that greys out.
 */
@Composable
private fun EpisodeSelectionBar(state: ItemDetailUiState, viewModel: ItemDetailViewModel) {
    val chosen = state.episodes.filter { it.episode.id in state.selectedIds }
    val any = chosen.isNotEmpty()

    SelectionBar(
        selectedCount = chosen.size,
        onClear = viewModel::clearSelection,
        onSelectAll = viewModel::selectAllVisible,
        actions = listOf(
            SelectionAction("Download", Icons.Default.Download, viewModel::downloadSelected, any),
            SelectionAction(
                "Add to queue",
                Icons.AutoMirrored.Filled.PlaylistAdd,
                viewModel::addSelectedToQueue,
                any,
            ),
            SelectionAction("Play next", Icons.Default.PlaylistPlay, viewModel::playSelectedNext, any),
            SelectionAction(
                "Remove download",
                Icons.Default.Delete,
                viewModel::removeSelectedDownloads,
                chosen.any { it.download != null },
            ),
        ),
    )
}

/**
 * One episode, told well enough to choose between it and the next one.
 *
 * The secondary line carries the number, the date and the length in that order, because
 * that is the order the questions come in: which one is this, is it the new one, and have
 * I got time for it. A title and a duration alone leave a feed of a thousand rows that
 * all look the same.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRowView(
    row: EpisodeRow,
    selectionActive: Boolean,
    isSelected: Boolean,
    onPlay: () -> Unit,
    onToggle: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subline = remember(row.episode) { episodeSubline(row.episode) }
    val background = if (isSelected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .combinedClickable(
                onClick = { if (selectionActive) onToggle() else onPlay() },
                onLongClick = onToggle,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionActive) {
            // The tick is not separately clickable: the whole row toggles, so a tap that
            // lands next to the box does the same thing as one that lands on it.
            Checkbox(checked = isSelected, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        ) {
            Text(
                row.episode.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subline,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.progressFraction > 0f) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { row.progressFraction },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            }
        }
        // The per-row controls stand down in selection mode: a bar that acts on eight
        // episodes and a button that acts on one, side by side, is a trap.
        if (!selectionActive) {
            DownloadButton(
                download = row.download,
                onDownload = onDownload,
                onRemove = onRemoveDownload,
                compact = true,
            )
            QueueMenu(
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                compact = true,
            )
        }
    }
}

/**
 * Play next, or add to the end.
 *
 * Behind a menu rather than as two more buttons: queueing is a deliberate act and a
 * rarer one than playing or downloading, and a row of four equal-weight controls makes
 * the two that matter harder to hit.
 */
@Composable
internal fun QueueMenu(
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val size = if (compact) 40.dp else 48.dp

    Box(modifier) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(size)) {
            Icon(Icons.Default.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Play next") },
                onClick = {
                    expanded = false
                    onPlayNext()
                },
                leadingIcon = { Icon(Icons.Default.PlaylistPlay, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("Add to queue") },
                onClick = {
                    expanded = false
                    onAddToQueue()
                },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
            )
        }
    }
}

/**
 * One control for the whole download lifecycle.
 *
 * Deliberately one button rather than separate download and delete affordances: the
 * state *is* the affordance, so there is never a delete button next to something not
 * downloaded, and never any doubt about whether a book is on the phone.
 */
@Composable
internal fun DownloadButton(
    download: DownloadStatus?,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val size = if (compact) 40.dp else 48.dp
    when {
        download == null || download.isFailed -> {
            IconButton(onClick = onDownload, modifier = modifier.size(size)) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = if (download == null) "Download" else "Retry download",
                    tint = if (download == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }

        download.isComplete -> {
            IconButton(onClick = onRemove, modifier = modifier.size(size)) {
                Icon(
                    Icons.Default.DownloadDone,
                    contentDescription = "Downloaded — tap to remove",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        else -> {
            // Tapping mid-download cancels. The ring shows real progress rather than an
            // indeterminate spinner, because a two-gigabyte book needs to look like it
            // is getting somewhere.
            Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { download.percent.coerceIn(0f, 1f) },
                    modifier = Modifier.size(size - 8.dp),
                    strokeWidth = 2.dp,
                )
                IconButton(onClick = onRemove, modifier = Modifier.size(size)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Cancel download",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** Hours and minutes; seconds only matter for short things. */
internal fun formatDuration(seconds: Double): String {
    if (seconds <= 0) return "—"
    val total = seconds.toLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${total}s"
    }
}

/** The whole secondary line, joined only from the parts the feed actually supplied. */
internal fun episodeSubline(
    episode: PodcastEpisode,
    nowMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String = listOfNotNull(
    formatEpisodeNumber(episode.season, episode.episodeNumber),
    formatPublished(episode.publishedAtMs, nowMs, zone),
    formatDuration(episode.durationSec).takeIf { episode.durationSec > 0 },
).joinToString(" · ")

/**
 * "S2 E14", or as much of it as the feed knows.
 *
 * Podcast feeds are inconsistent about both fields, and a row reading "S E" or "Season
 * null" is worse than a row that simply does not mention the numbering.
 */
internal fun formatEpisodeNumber(season: String?, episodeNumber: String?): String? {
    val seasonPart = season?.trim()?.takeIf { it.isNotEmpty() }?.let { "S$it" }
    val episodePart = episodeNumber?.trim()?.takeIf { it.isNotEmpty() }?.let { "E$it" }
    return listOfNotNull(seasonPart, episodePart).takeIf { it.isNotEmpty() }?.joinToString(" ")
}

/**
 * Relative for the last week, absolute after that.
 *
 * The question a date answers on a podcast is "is this the new one", and for anything
 * from the past few days "3 days ago" answers it without arithmetic. Past that the
 * relative form stops helping — nobody counts back forty days — so it becomes a date, and
 * the year appears only when it is not the current one, because a year on every row is
 * noise on every row.
 *
 * Counted in calendar days rather than in twenty-four hour blocks: an episode published
 * late last night is "Yesterday", not "Today", however few hours ago it was.
 */
internal fun formatPublished(
    publishedAtMs: Long,
    nowMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    if (publishedAtMs <= 0L) return null
    // Instant.atZone rather than LocalDate.ofInstant: the latter is a Java 9 addition and
    // is missing from the java.time that ships with API 26.
    val published = Instant.ofEpochMilli(publishedAtMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(published, today)
    return when {
        days == 0L -> "Today"
        days == 1L -> "Yesterday"
        days in 2L..6L -> "$days days ago"
        // A future date is a clock or a feed being wrong; showing it plainly is more
        // honest than "-2 days ago".
        published.year == today.year -> published.format(SAME_YEAR)
        else -> published.format(OTHER_YEAR)
    }
}

/** "48 of 1,204 episodes", and just the total when nothing is hidden. */
internal fun episodeCountLine(visible: Int, total: Int): String = when {
    total == 1 && visible == 1 -> "1 episode"
    visible == total -> "%,d episodes".format(Locale.UK, total)
    else -> "%,d of %,d episodes".format(Locale.UK, visible, total)
}

// British ordering, and fixed rather than following the device locale: the rest of the
// screen is written in one language, and a row reading "12 mars" beside it is not an
// improvement until the whole app is translated.
private val SAME_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
private val OTHER_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
