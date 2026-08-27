package io.github.lightheaded.lugu.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.lightheaded.lugu.core.download.DownloadStatus
import io.github.lightheaded.lugu.core.model.EpisodeSort
import io.github.lightheaded.lugu.core.model.LibraryItem
import io.github.lightheaded.lugu.core.model.ListFilter
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.model.PodcastEpisode
import io.github.lightheaded.lugu.core.model.PodcastTrim
import io.github.lightheaded.lugu.core.model.Series
import io.github.lightheaded.lugu.core.model.SeriesRef
import io.github.lightheaded.lugu.core.model.formatLengthCompact
import io.github.lightheaded.lugu.core.model.formatShortSeconds
import io.github.lightheaded.lugu.core.sync.BrowseKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(
    onBack: () -> Unit,
    onPlay: (itemId: String, episodeId: String?) -> Unit,
    onOpenEpisode: (itemId: String, episodeId: String) -> Unit,
    onBrowseGroup: (kind: String, name: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ItemDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val item = state.item
    val snackbarHostState = remember { SnackbarHostState() }
    var showCollections by remember { mutableStateOf(false) }
    // Saveable, unlike the collections dialog: the trim controls are where a rotation is
    // most likely to happen mid-edit, and folding them away underneath somebody choosing a
    // number is worse than the row of state it costs to remember.
    var trimExpanded by rememberSaveable { mutableStateOf(false) }

    // The collections pull is the heaviest request the app makes, so it is tied to opening
    // the list rather than to opening the page: almost nobody who reads a book's page wants
    // to know which collections hold it.
    LaunchedEffect(showCollections) {
        if (showCollections) viewModel.refreshCollections()
    }

    // A refusal is shown as an overlay, not as inline content: making the page jump to
    // report a failed button press is worse than the failure.
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissMessage()
    }

    val downloadUndo by viewModel.undo.collectAsStateWithLifecycle()
    val noticeMillis by viewModel.noticeMillis.collectAsStateWithLifecycle()

    // The delete or cancel has already happened; this is the way back, offered for as
    // long as the notice setting says and no longer. Letting it time out keeps the
    // change, which is what the tap asked for — the same shape as the player's own
    // notices, because it is the same promise.
    LaunchedEffect(downloadUndo, noticeMillis) {
        val pending = downloadUndo ?: return@LaunchedEffect
        val result = withTimeoutOrNull(noticeMillis) {
            snackbarHostState.showSnackbar(
                message = pending.text,
                actionLabel = "Undo",
                withDismissAction = true,
                duration = SnackbarDuration.Indefinite,
            )
        }
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDownload() else viewModel.dismissDownloadUndo()
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
                    actions = { state.webUrl?.let { WebClientAction(url = it) } },
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
                        item.subtitle?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            // The dash is this line's decision rather than the formatter's:
                            // a podcast carries no item-level duration at all, and "0s"
                            // under the cover reads as a show with no audio in it instead
                            // of as a length there was never a figure for.
                            item.durationSec.takeIf { it > 0 }?.let(::formatLengthCompact) ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Skipped entirely rather than left to render nothing: the list spaces its
            // items apart, so an empty row still leaves a gap where it was.
            if (item.hasBrowseLinks) {
                item {
                    BrowseGroupLinks(item = item, series = state.series, onBrowseGroup = onBrowseGroup)
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
                            // No dash here: this line only exists once there is progress to
                            // draw, so both figures are known by construction.
                            "${formatLengthCompact(state.positionSec)} of " +
                                formatLengthCompact(item.durationSec),
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
                        RowActionsMenu(
                            onPlayNext = { viewModel.playNext() },
                            onAddToQueue = { viewModel.addToQueue() },
                            isFinished = state.isFinished,
                            onSetFinished = { viewModel.setFinished(it) },
                            onOpenCollections = { showCollections = true },
                        )
                    }
                }
            }

            if (item.mediaType == MediaType.PODCAST) {
                state.playTarget?.let { target ->
                    item { PodcastPlayRow(target = target, onPlay = { onPlay(item.id, it) }) }
                }
                // Below the play row and not beside it, and present even when the play row
                // is not: a feed the server holds no episodes of is exactly the feed that
                // needs this button, and it is the one case with nothing to play.
                item {
                    GetNewEpisodesRow(
                        fetching = state.fetchingNewEpisodes,
                        onGetNewEpisodes = viewModel::getNewEpisodes,
                    )
                }
            }

            item.description?.takeIf { it.isNotBlank() }?.let { description ->
                item {
                    // A show's description is markup exactly as an episode's is, and it was
                    // drawn with a plain Text until the episode page needed the answer — so
                    // a podcast whose description uses <p> was showing its tags here.
                    ShowNotesText(html = description, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Only a podcast has a trim, and only a podcast's page offers one: an intro is
            // the same fifteen seconds on every episode of one show, which is what makes it
            // worth setting once here rather than in a settings screen listing every show.
            if (item.mediaType == MediaType.PODCAST) {
                item {
                    PodcastTrimSection(
                        trim = state.trim,
                        isOwn = state.trimIsOwn,
                        expanded = trimExpanded,
                        onExpandedChange = { trimExpanded = it },
                        onTrimChange = viewModel::setTrim,
                        onUseDefault = viewModel::useDefaultTrim,
                    )
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
                    onOpen = { onOpenEpisode(item.id, row.episode.id) },
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
                    onSetFinished = { viewModel.setFinished(it, row.episode.id) },
                )
            }
        }

        if (showCollections) {
            CollectionMembershipDialog(
                collections = state.collections,
                onToggle = viewModel::setInCollection,
                onDismiss = { showCollections = false },
            )
        }
    }
}

/**
 * Which collections hold this book, and a tick to change that.
 *
 * A dialog rather than a second layer of the menu it is opened from. There can be dozens of
 * collections on a server, and a dropdown nested inside a dropdown is both hard to scroll
 * and easy to dismiss by accident — which here would mean dismissing it mid-edit.
 *
 * It stays open after a tick, because adding a book to two collections is one errand rather
 * than two, and each tick is committed to the server on its own as it is made. Nothing is
 * shown optimistically: the box moves when the server has agreed, and if it will not agree
 * — offline, most often — the reason arrives as a message underneath.
 */
@Composable
private fun CollectionMembershipDialog(
    collections: List<CollectionChoice>,
    onToggle: (collectionId: String, inCollection: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text("Collections") },
        text = {
            if (collections.isEmpty()) {
                // This app can put a book into a collection but cannot make one, so an
                // empty list has to say where collections come from. "None" on its own
                // reads as a failure to load.
                Text(
                    "No collections in this library yet. They are made on the server.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@AlertDialog
            }
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(collections, key = { it.id }) { choice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            // The whole row toggles, so a tap that lands beside the box
                            // does the same thing as one that lands on it.
                            .clickable { onToggle(choice.id, !choice.contains) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = choice.contains, onCheckedChange = null)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            choice.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

/**
 * The actions worth offering on a handful of episodes at once.
 *
 * Remove download is present but disabled unless something in the selection is actually
 * on the phone: hiding it instead would make the bar's contents shift about between
 * selections, and a control that moves is harder to aim at than one that greys out.
 *
 * Marking is one button rather than two, because a bar of six equal icons is a bar nobody
 * reads. Which of the two it offers is decided by [markFinishedTarget].
 */
@Composable
private fun EpisodeSelectionBar(state: ItemDetailUiState, viewModel: ItemDetailViewModel) {
    val chosen = state.episodes.filter { it.episode.id in state.selectedIds }
    val any = chosen.isNotEmpty()
    val finishing = markFinishedTarget(chosen)

    SelectionBar(
        selectedCount = chosen.size,
        onClear = viewModel::clearSelection,
        onSelectAll = viewModel::selectAllVisible,
        actions = listOf(
            SelectionAction(
                if (finishing) "Mark as finished" else "Mark as not finished",
                if (finishing) Icons.Default.DoneAll else Icons.Default.RemoveDone,
                { viewModel.setSelectedFinished(finishing) },
                any,
            ),
            SelectionAction("Download", Icons.Default.Download, viewModel::downloadSelected, any),
            SelectionAction(
                "Add to queue",
                Icons.AutoMirrored.Filled.PlaylistAdd,
                viewModel::addSelectedToQueue,
                any,
            ),
            SelectionAction("Play next", Icons.AutoMirrored.Default.PlaylistPlay, viewModel::playSelectedNext, any),
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
 *
 * ### What a tap means, and why it changed
 *
 * A tap opens the episode page; the play button beside the row plays it. Until the page
 * existed a tap played, and that was itself a fix — the player used to open showing Play,
 * inviting a press it could not honour. Making the tap open a page and leaving play to a
 * button of its own is the reverse of what was first proposed for this work, which was to
 * hide the page behind the overflow and leave the tap alone. Tom decided otherwise, and
 * the reason holds: the row already says the title, the number, the date and the length,
 * so the only thing left to open the row *for* is the notes, and a listener who has
 * already decided has a button that is quicker than the tap ever was.
 *
 * The right-hand side is therefore three compact controls — play, download, more — rather
 * than the two it was. That is the ceiling. A fourth would start to squeeze the title,
 * which is the part of the row that tells one episode from another, and everything else
 * worth offering is already inside the overflow.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EpisodeRowView(
    row: EpisodeRow,
    selectionActive: Boolean,
    isSelected: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onToggle: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onSetFinished: (Boolean) -> Unit,
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
                onClick = { if (selectionActive) onToggle() else onOpen() },
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
            IconButton(onClick = onPlay, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.PlayArrow,
                    // Named after the episode rather than left as "Play". A screen reader
                    // going down this list would otherwise read the same word on every
                    // row, with nothing to say which one it is about.
                    contentDescription = "Play ${row.episode.title}",
                )
            }
            DownloadButton(
                download = row.download,
                onDownload = onDownload,
                onRemove = onRemoveDownload,
                compact = true,
            )
            RowActionsMenu(
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                compact = true,
                isFinished = row.isFinished,
                onSetFinished = onSetFinished,
            )
        }
    }
}

/**
 * The way out to the server's own web client, for what lugu cannot do yet.
 *
 * A plain button rather than an overflow menu, because it is the only action this bar has and
 * hiding a single item behind three dots is a tap spent on nothing. It is described as "open
 * in your browser" rather than named after the web client: leaving the app is the surprise
 * worth announcing, and which client is at the other end is not what anyone is deciding.
 *
 * See [io.github.lightheaded.lugu.core.model.WebClient] for what does not survive the journey.
 */
@Composable
private fun WebClientAction(url: String) {
    // Wrapped because a phone with no browser installed throws rather than declining, and a
    // link out is not worth crashing a book's page over.
    val uriHandler = LocalUriHandler.current
    IconButton(onClick = { runCatching { uriHandler.openUri(url) } }) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in your browser")
    }
}

/**
 * Play next, add to the end, mark the row finished, and change which collections hold it.
 *
 * Behind a menu rather than as more buttons: queueing, marking and filing are deliberate
 * acts and rarer ones than playing or downloading, and a row of five equal-weight controls
 * makes the two that matter harder to hit.
 *
 * [onSetFinished] and [onOpenCollections] are both optional so that a row they mean nothing
 * for simply does not offer them. A podcast episode is neither: it has its own finished
 * mark, but collections hold library items rather than episodes, so it gets the first and
 * not the second.
 */
@Composable
internal fun RowActionsMenu(
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    isFinished: Boolean = false,
    onSetFinished: ((Boolean) -> Unit)? = null,
    onOpenCollections: (() -> Unit)? = null,
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
                leadingIcon = { Icon(Icons.AutoMirrored.Default.PlaylistPlay, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("Add to queue") },
                onClick = {
                    expanded = false
                    onAddToQueue()
                },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
            )
            onOpenCollections?.let { open ->
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Collections") },
                    onClick = {
                        expanded = false
                        open()
                    },
                    leadingIcon = { Icon(Icons.Default.Bookmarks, contentDescription = null) },
                )
            }
            onSetFinished?.let { setFinished ->
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(if (isFinished) "Mark as not finished" else "Mark as finished") },
                    onClick = {
                        expanded = false
                        setFinished(!isFinished)
                    },
                    leadingIcon = {
                        Icon(
                            if (isFinished) Icons.Default.RemoveDone else Icons.Default.DoneAll,
                            contentDescription = null,
                        )
                    },
                )
            }
        }
    }
}

/**
 * What this show skips at the start, at the end, and in the middle.
 *
 * Folded away behind one row, because it is a thing done once per show and then never
 * again: a listener who has told lugu about a fifteen-second sting should not have to read
 * about it every time they open the feed. The row itself stays visible and says what the
 * current answer is, which is the part that has to be readable without opening anything.
 *
 * The status line is the whole reason this is not three plain controls. A show set to trim
 * nothing and a show following a default of nothing show identical numbers, and they are
 * not the same: change the default later and only the second one moves. So the line names
 * which of the two it is, and the way back to the default is a button rather than an
 * inference from setting everything to zero.
 *
 * On adverts, and what the switch does not promise. It skips chapters whose own titles say
 * they are advertising, which is all an episode's own markers can support — see the
 * reasoning written out on `SkipRegions`. An unmarked advert needs audio fingerprinting
 * against a database of known adverts, and a false positive there eats a minute of the
 * show, so nothing here looks for one. The copy says so in as many words: a switch that
 * reads as "skip the adverts" and then plays them is worse than no switch.
 */
@Composable
internal fun PodcastTrimSection(
    trim: PodcastTrim,
    isOwn: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onTrimChange: (PodcastTrim) -> Unit,
    onUseDefault: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { onExpandedChange(!expanded) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Skipping", style = MaterialTheme.typography.titleSmall)
                Text(
                    trimStatusLine(trim, isOwn),
                    style = MaterialTheme.typography.labelMedium,
                    // Its own trim is said in the accent colour and the default in the quiet
                    // one, so the two are told apart at a glance as well as in words.
                    color = if (isOwn) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Hide skipping" else "Change skipping",
            )
        }

        if (!expanded) return@Column

        TrimChoiceRow(
            title = "Intro",
            subtitle = "Cut from the start of every episode of this show.",
            seconds = trim.introSec,
            onSelect = { onTrimChange(trim.copy(introSec = it)) },
        )
        TrimChoiceRow(
            title = "Outro",
            subtitle = "Cut from the end of every episode of this show.",
            seconds = trim.outroSec,
            onSelect = { onTrimChange(trim.copy(outroSec = it)) },
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Skip marked adverts", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Skips chapters the episode itself names as advertising. An advert the " +
                        "episode does not mark cannot be found, so this will miss any show " +
                        "that does not chapter its ad breaks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(8.dp))
            Switch(
                checked = trim.skipMarkedAdverts,
                onCheckedChange = { onTrimChange(trim.copy(skipMarkedAdverts = it)) },
            )
        }

        // Offered only where it would do something. A show already on the default has
        // nothing to go back to, and a button that is its own no-op teaches nothing about
        // which of the two states the show is in.
        if (isOwn) {
            TextButton(onClick = onUseDefault) { Text("Use the default again") }
        }
    }
}

/** One trim length, as a row of the choices worth one tap. */
@Composable
private fun TrimChoiceRow(
    title: String,
    subtitle: String,
    seconds: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            items(PodcastTrim.TRIM_CHOICES_SEC) { choice ->
                FilterChip(
                    selected = choice == seconds,
                    onClick = { onSelect(choice) },
                    label = { Text(trimChoiceLabel(choice), maxLines = 1, softWrap = false) },
                )
            }
        }
    }
}

/**
 * What the show is skipping, and on whose say-so.
 *
 * Both halves are load-bearing. The second says what is being cut, so nobody has to open
 * the controls to find out; the first says whether these numbers are this show's or the
 * default's, which is the difference between "I set this to nothing" and "nothing has been
 * set" — states that are identical on screen and behave differently the day the default
 * changes.
 */
internal fun trimStatusLine(trim: PodcastTrim, isOwn: Boolean): String {
    val source = if (isOwn) "Set for this show" else "Following the default"
    val parts = listOfNotNull(
        trim.introSec.takeIf { it > 0 }?.let { "${formatShortSeconds(it)} intro" },
        trim.outroSec.takeIf { it > 0 }?.let { "${formatShortSeconds(it)} outro" },
        "marked adverts".takeIf { trim.skipMarkedAdverts },
    )
    return if (parts.isEmpty()) "$source — nothing skipped" else "$source — ${parts.joinToString(", ")}"
}

/** "None" rather than "0s": a chip reading zero looks like a length, not like an off switch. */
internal fun trimChoiceLabel(seconds: Int): String =
    if (seconds <= 0) "None" else formatShortSeconds(seconds)

/** Whether there is anything to link to at all: a podcast often has none of the three. */
private val LibraryItem.hasBrowseLinks: Boolean
    get() = listOfNotNull(authorName, narratorName, seriesName).any { it.isNotBlank() }

/**
 * The author, the narrator and the series, as links to their own pages.
 *
 * A block of its own below the cover rather than three lines squeezed beside it: a link
 * has to be hittable, and there is no room for three forty-eight-dip targets in a column
 * next to a hundred-and-forty-dip cover. Keeping the three together also makes them look
 * like what they are — the same kind of thing, leading to the same kind of page.
 *
 * The series is linked by its *title*, never by the string the server sends: that string
 * has the volume number baked into it, so linking with it would lead to a page holding
 * exactly one book.
 */
@Composable
private fun BrowseGroupLinks(
    item: LibraryItem,
    series: List<SeriesRef>,
    onBrowseGroup: (kind: String, name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    /*
     * The memberships, falling back to the rendered string only while the mirror has none.
     *
     * The fallback exists for the moment after an upgrade and before the first sync, and
     * for an item the series sync has not reached. It is the old reading and carries the
     * old flaw — a book in two series renders as one phantom named after both — so it is
     * the second choice rather than the first, and it disappears as soon as there is a
     * real membership to draw.
     */
    val entries = remember(series, item.seriesName) {
        series.ifEmpty {
            listOfNotNull(
                Series.titleOf(item.seriesName)?.let {
                    SeriesRef(id = null, name = it, sequence = Series.sequenceOf(item.seriesName))
                },
            )
        }
    }

    Column(modifier) {
        item.authorName?.takeIf { it.isNotBlank() }?.let { author ->
            BrowseGroupLink(
                name = author,
                onClick = { onBrowseGroup(BrowseKind.AUTHORS.id, author) },
            )
        }
        item.narratorName?.takeIf { it.isNotBlank() }?.let { narrator ->
            BrowseGroupLink(
                name = narrator,
                onClick = { onBrowseGroup(BrowseKind.NARRATORS.id, narrator) },
                prefix = "Read by",
            )
        }
        // One link per series, because a book that ends one trilogy and opens another is
        // two facts about it and only ever showed as one.
        entries.forEach { membership ->
            BrowseGroupLink(
                name = membership.name,
                onClick = { onBrowseGroup(BrowseKind.SERIES.id, membership.name) },
                prefix = seriesSequenceLabel(membership.sequence)?.let { "$it of" } ?: "Part of",
            )
        }
    }
}

/**
 * One link out of the item page.
 *
 * Coloured with the primary colour and given a full-width row to be tapped in, because a
 * name that merely responds to a tap is not discoverable: nobody presses text to find out
 * whether it does anything. The prefix stays in the ordinary colour so that only the part
 * that leads somewhere looks as though it does.
 */
@Composable
private fun BrowseGroupLink(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prefix: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        prefix?.let {
            Text(
                "$it ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Whether the bar's one marking action should finish the picked episodes or un-finish them.
 *
 * Finishing is the offer unless every episode picked is already finished, which is the
 * only selection where it would do nothing at all. Deciding it from the selection rather
 * than offering both keeps the bar to one button, and means the button is never a no-op.
 *
 * An empty selection reads as finishing too. The button is disabled there, and a disabled
 * button that says "Mark as not finished" invites the wrong guess about what it will do.
 */
internal fun markFinishedTarget(rows: List<EpisodeRow>): Boolean =
    rows.isEmpty() || rows.any { !it.isFinished }

/**
 * A podcast's own primary action, the counterpart of the book's play row.
 *
 * One tap, and the button says what it is about to play. The caller decides whether there
 * is anything to play at all: [PodcastPlayTarget] is null for a feed with no episodes, and
 * the row is left out. See [podcastPlayTarget] for the rule that picks the episode,
 * including what happens after every episode is marked finished.
 *
 * A composable of its own so that the screenshot test can photograph it. It was composed
 * in place until then, which put the one control the page leads with outside every picture.
 */
@Composable
internal fun PodcastPlayRow(
    target: PodcastPlayTarget,
    onPlay: (episodeId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(onClick = { onPlay(target.episodeId) }, modifier = modifier.fillMaxWidth()) {
        Icon(Icons.Default.PlayArrow, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(target.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Asks the server to read this show's feed and fetch the episodes it does not hold.
 *
 * This is the server-side fetch and never the phone-side download, which every episode row
 * carries its own control for. The words say "get" rather than "download" to keep the two
 * apart, and the icon is the feed rather than a cloud for the same reason.
 *
 * Secondary emphasis, because the page leads with playing something. No confirmation and no
 * undo: a request to fetch destroys nothing. The outcome arrives as a message on the
 * screen's snackbar, so the page does not move to report it.
 *
 * While the request runs, the icon becomes a spinner and the button refuses a second press.
 * The label does not change and the button keeps its height, so nothing moves under the
 * finger that has just pressed it.
 */
@Composable
internal fun GetNewEpisodesRow(
    fetching: Boolean,
    onGetNewEpisodes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onGetNewEpisodes,
        enabled = !fetching,
        modifier = modifier.fillMaxWidth(),
    ) {
        if (fetching) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.RssFeed, contentDescription = null)
        }
        Spacer(Modifier.size(8.dp))
        Text("Get new episodes")
    }
}

/** The episode a podcast's hero button plays, and how the button names it. */
data class PodcastPlayTarget(val episodeId: String, val label: String)

/**
 * Which episode a podcast's hero button plays, and what it says about it.
 *
 * Continues whatever is already in progress, so a return visit picks up where the last one
 * stopped. More than one episode can carry a partial position at once, so ties are broken
 * by whichever was touched most recently.
 *
 * With nothing in progress, it offers the newest unfinished episode — "newest" meaning what
 * the episode list itself means by it, [PodcastEpisode.publishedAtMs] descending, the same
 * field [EpisodeSort.NEWEST] sorts on, not when the episode was added to the phone.
 *
 * A podcast with every episode marked finished has no unfinished episode to offer. Rather
 * than leave the button with nothing to do, it falls back to the newest episode and plays
 * it again — said plainly in the label, so a listener is not surprised to hear the start of
 * something they have already finished.
 */
internal fun podcastPlayTarget(episodes: List<EpisodeRow>): PodcastPlayTarget? {
    val inProgress = episodes
        .filter { it.progressFraction > 0f && !it.isFinished }
        .maxWithOrNull(
            compareBy<EpisodeRow> { it.progress?.lastUpdateMs ?: 0L }
                .thenBy { it.episode.publishedAtMs },
        )
    if (inProgress != null) {
        return PodcastPlayTarget(inProgress.episode.id, "Continue: ${inProgress.episode.title}")
    }

    val newestUnfinished = episodes.filter { !it.isFinished }.maxByOrNull { it.episode.publishedAtMs }
    if (newestUnfinished != null) {
        return PodcastPlayTarget(newestUnfinished.episode.id, "Play latest episode")
    }

    val newest = episodes.maxByOrNull { it.episode.publishedAtMs } ?: return null
    return PodcastPlayTarget(newest.episode.id, "Play latest episode again")
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
                    // Says what the tap does, the same way "Cancel download" already does
                    // below — the glyph itself still just says "downloaded", since that is
                    // what a screenshot baseline draws and this file cannot re-record one.
                    contentDescription = "Delete download",
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

/** The whole secondary line, joined only from the parts the feed actually supplied. */
internal fun episodeSubline(
    episode: PodcastEpisode,
    nowMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String = listOfNotNull(
    formatEpisodeNumber(episode.season, episode.episodeNumber),
    formatPublished(episode.publishedAtMs, nowMs, zone),
    episode.durationSec.takeIf { it > 0 }?.let(::formatLengthCompact),
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
