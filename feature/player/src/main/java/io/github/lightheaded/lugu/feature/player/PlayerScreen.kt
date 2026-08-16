package io.github.lightheaded.lugu.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.github.lightheaded.lugu.core.model.formatClock
import io.github.lightheaded.lugu.core.model.formatSpeedNumber
import io.github.lightheaded.lugu.core.sync.PlayerSettings
import io.github.lightheaded.lugu.core.sync.TransportButton
import io.github.lightheaded.lugu.playback.PositionJump

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val jump by viewModel.pendingJump.collectAsStateWithLifecycle()
    val rewindNotice by viewModel.rewindNotice.collectAsStateWithLifecycle()
    val continuation by viewModel.continuation.collectAsStateWithLifecycle()
    val sleep by viewModel.sleepTimer.collectAsStateWithLifecycle()
    var showSleepSheet by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }
    val history by viewModel.positionHistory.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by viewModel.settings.collectAsStateWithLifecycle(initialValue = PlayerSettings())
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showChapterSheet by remember { mutableStateOf(false) }
    var showBookmarkSheet by remember { mutableStateOf(false) }
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val canBookmark by viewModel.canBookmark.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    /*
     * Both notices are overlays and neither is inline content, so announcing a
     * correction never reflows the screen. An inline banner made the cover art and the
     * whole transport shift down as it appeared and back up as it went — which is a
     * worse interruption than the thing being announced.
     *
     * `Indefinite` plus an explicit timeout rather than a `SnackbarDuration`: the
     * built-in durations are four and ten seconds and neither is configurable, and a
     * notice carrying an Undo has to stay up long enough to read a timestamp and decide.
     * Cancelling the coroutine is what dismisses the snackbar.
     */
    val noticeMillis = settings.noticeSeconds.coerceAtLeast(1) * 1000L

    LaunchedEffect(rewindNotice, noticeMillis) {
        rewindNotice?.let {
            withTimeoutOrNull(noticeMillis) {
                snackbarHostState.showSnackbar(
                    message = it,
                    withDismissAction = true,
                    duration = SnackbarDuration.Indefinite,
                )
            }
            viewModel.dismissRewindNotice()
        }
    }

    /*
     * A position adopted from another device is announced, never silent. An automatic
     * correction the listener cannot see is indistinguishable from the app losing their
     * place — which is the complaint lugu exists to answer. Letting it time out keeps
     * the new position, which is the same as the old "Keep" button without the
     * second button.
     */
    LaunchedEffect(jump, noticeMillis) {
        jump?.let { pending ->
            val result = withTimeoutOrNull(noticeMillis) {
                snackbarHostState.showSnackbar(
                    // Where the app knows why it moved the position, it says why, and the
                    // numbers stay on as the supporting detail. "Jumped from 0:00 to
                    // 0:15" is a true account of an intro being skipped that explains
                    // none of it, and an unexplained correction is indistinguishable from
                    // the app having lost the listener's place. A jump with no better
                    // account than its own numbers keeps the original wording.
                    message = "${pending.reason ?: "Jumped"} from " +
                        "${formatClock(pending.fromSec)} to ${formatClock(pending.toSec)}",
                    actionLabel = "Undo",
                    withDismissAction = true,
                    duration = SnackbarDuration.Indefinite,
                )
            }
            if (result == SnackbarResult.ActionPerformed) viewModel.undoJump() else viewModel.dismissJump()
        }
    }

    /*
     * Something the listener did not choose is now loaded, and it says so.
     *
     * A cued suggestion carries a Play button, because a book waiting silently with no
     * explanation is indistinguishable from playback having stopped for a reason nobody
     * can see. One that started on its own carries only its reason — the transport
     * already offers the way to stop it.
     */
    LaunchedEffect(continuation, noticeMillis) {
        val notice = continuation ?: return@LaunchedEffect
        val text = when {
            notice.cued -> notice.reason?.let { "$it — ready to play" } ?: "Ready to play"
            else -> notice.reason ?: return@LaunchedEffect
        }
        val result = withTimeoutOrNull(noticeMillis) {
            snackbarHostState.showSnackbar(
                message = text,
                actionLabel = if (notice.cued) "Play" else null,
                withDismissAction = true,
                duration = SnackbarDuration.Indefinite,
            )
        }
        if (result == SnackbarResult.ActionPerformed) viewModel.togglePlayPause()
        viewModel.dismissContinuationNotice()
    }

    var scrubbing by remember { mutableStateOf<Float?>(null) }

    if (showSpeedSheet) {
        SpeedSheet(
            current = state.speed,
            presets = settings.speed.presets,
            onPick = viewModel::setSpeed,
            onDismiss = { showSpeedSheet = false },
        )
    }

    if (showHistorySheet) {
        PositionHistorySheet(
            history = history,
            onRestore = {
                viewModel.restorePosition(it)
                showHistorySheet = false
            },
            onDismiss = { showHistorySheet = false },
        )
    }

    if (showSleepSheet) {
        SleepTimerSheet(
            hasChapters = state.chapterCount > 1,
            presets = viewModel.sleepPresets,
            timer = sleep,
            settings = settings.sleep,
            speed = state.speed,
            onPick = {
                viewModel.setSleepTimer(it)
                showSleepSheet = false
            },
            onExtend = viewModel::extendSleepTimer,
            onDismiss = { showSleepSheet = false },
        )
    }

    if (showChapterSheet) {
        ChapterSheet(
            chapters = nowPlaying?.chapters.orEmpty(),
            positionSec = state.positionSec,
            onSeek = {
                viewModel.seekTo(it)
                showChapterSheet = false
            },
            onDismiss = { showChapterSheet = false },
        )
    }

    if (showBookmarkSheet) {
        BookmarkSheet(
            bookmarks = bookmarks,
            speed = state.speed,
            onSeek = {
                viewModel.seekTo(it)
                showBookmarkSheet = false
            },
            onRename = viewModel::renameBookmark,
            onDelete = viewModel::removeBookmark,
            onDismiss = { showBookmarkSheet = false },
        )
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Now playing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            AsyncImage(
                model = nowPlaying?.coverUrl,
                contentDescription = nowPlaying?.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    // The cover is the biggest thing on the screen and looks like the
                    // book, so it goes where the book does. Clipped first, so the ripple
                    // follows the corners rather than the square behind them.
                    .clickable(enabled = nowPlaying != null) {
                        nowPlaying?.libraryItemId?.let(onOpenItem)
                    },
            )

            Spacer(Modifier.height(24.dp))
            Text(
                nowPlaying?.title ?: "Nothing playing",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // The title is a link to the item wherever it is shown.
                modifier = Modifier.clickable(enabled = nowPlaying != null) {
                    nowPlaying?.libraryItemId?.let(onOpenItem)
                },
            )
            nowPlaying?.author?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.chapter?.let { chapter ->
                /*
                 * The readout is the way into the chapter list, because it is already
                 * what someone is looking at when they wonder where they are. An item
                 * with no chapters to choose between keeps the readout and loses the
                 * tap: an affordance that opens an empty sheet is worse than none.
                 */
                val hasChapterList = state.chapterCount > 1
                Spacer(Modifier.height(8.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = hasChapterList) { showChapterSheet = true }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        chapter.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (hasChapterList) {
                        Text(
                            "Chapter ${state.chapterIndex + 1} of ${state.chapterCount} · " +
                                "${formatClock(state.chapterPositionSec)} / " +
                                formatClock(state.chapterDurationSec),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Slider(
                value = scrubbing ?: state.positionSec.toFloat(),
                onValueChange = { scrubbing = it },
                onValueChangeFinished = {
                    scrubbing?.let { viewModel.seekTo(it.toDouble()) }
                    scrubbing = null
                },
                valueRange = 0f..(state.durationSec.toFloat().coerceAtLeast(1f)),
                modifier = Modifier.semantics { contentDescription = "Playback position" },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    formatClock(scrubbing?.toDouble() ?: state.positionSec),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    "-${formatClock(state.durationSec - (scrubbing?.toDouble() ?: state.positionSec))}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Spacer(Modifier.height(24.dp))
            /*
             * Laid out by how often each control is actually used: seeking back to
             * re-hear a missed sentence dominates, finding a place is next, and chapter
             * skipping is occasional. So the seek pair flanks play/pause at full size
             * and the chapter pair sits outside it, smaller and dimmer.
             */
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (TransportButton.PREVIOUS_CHAPTER in settings.playerButtons) {
                    IconButton(
                        onClick = viewModel::previousChapter,
                        enabled = state.chapterCount > 1,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Previous chapter",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                FilledTonalIconButton(
                    onClick = { viewModel.seekBy(-settings.skipBackSec.toDouble()) },
                    modifier = Modifier.size(60.dp),
                ) {
                    SeekIcon(
                        seconds = settings.skipBackSec,
                        icon = Icons.Default.Replay10,
                        description = "Back ${settings.skipBackSec} seconds",
                    )
                }

                FilledIconButton(
                    onClick = viewModel::togglePlayPause,
                    modifier = Modifier.size(76.dp),
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(38.dp),
                    )
                }

                FilledTonalIconButton(
                    onClick = { viewModel.seekBy(settings.skipForwardSec.toDouble()) },
                    modifier = Modifier.size(60.dp),
                ) {
                    SeekIcon(
                        seconds = settings.skipForwardSec,
                        icon = Icons.Default.Forward30,
                        description = "Forward ${settings.skipForwardSec} seconds",
                    )
                }

                if (TransportButton.NEXT_CHAPTER in settings.playerButtons) {
                    IconButton(
                        onClick = viewModel::nextChapter,
                        enabled = state.chapterCount > 1,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Next chapter",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            PlayerActionRow(
                speed = state.speed,
                sleepArmed = sleep.isArmed,
                canBookmark = canBookmark,
                onSpeed = { showSpeedSheet = true },
                onSleep = { showSleepSheet = true },
                onAddBookmark = {
                    viewModel.addBookmark()
                    // The write lands in Room before the server hears about it, so the
                    // confirmation is honest even with no signal.
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "Bookmarked at ${formatClock(state.positionSec)}",
                            withDismissAction = true,
                        )
                    }
                },
                onBookmarks = { showBookmarkSheet = true },
                onHistory = { showHistorySheet = true },
            )

            Spacer(Modifier.height(8.dp))
            state.error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (nowPlaying?.isTranscoded == true) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Transcoding — seeking is less precise",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Compact bar shown above the library so playback is always one tap away. */
@Composable
fun MiniPlayer(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val current = nowPlaying ?: return

    MiniPlayerBar(
        title = current.title,
        subtitle = state.chapter?.title ?: current.author.orEmpty(),
        coverUrl = current.coverUrl,
        progress = if (state.durationSec > 0) (state.positionSec / state.durationSec).toFloat() else 0f,
        isPlaying = state.isPlaying,
        onOpen = onOpen,
        onPlayPause = viewModel::togglePlayPause,
        modifier = modifier,
    )
}

/**
 * The mini player as it is drawn, with no player attached.
 *
 * Split from [MiniPlayer] so the bar can be photographed sitting on the tab bar, which is
 * the whole subject of the separation below and is not testable through a bound media
 * session.
 *
 * On telling it apart from the tab bar. The two used to read as one slab, and honestly so:
 * `NavigationBar` fills itself with `surfaceContainer` and this bar was painted with the
 * same colour, so there was no edge between them in either theme. Two things are done
 * about it, because either alone is thin. The bar is lifted one step to
 * `surfaceContainerHigh`, which separates it from the tab bar in the direction each theme
 * expects — lighter in the dark, darker in the light. And a hairline of `outlineVariant`
 * closes the bottom edge, which is the part that survives an unusual palette: a tonal step
 * is one step of whatever the scheme happens to be, and dynamic colour can make that step
 * almost nothing, whereas an outline colour is specified to be visible against the
 * surfaces around it. The divider belongs to this bar rather than to the shell so that it
 * appears and disappears with the bar; a line the shell drew would sit above the tab bar
 * with nothing over it whenever nothing was playing.
 */
@Composable
internal fun MiniPlayerBar(
    title: String,
    subtitle: String,
    coverUrl: String?,
    progress: Float,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                )
            }
        }
        HorizontalDivider(thickness = Dp.Hairline, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * Recovery for a position that moved unexpectedly.
 *
 * Exists because a notification button once seeked a book to zero and there was no way
 * back: the database holds only *current* progress, so an accidental jump was
 * permanent. Every large move is now recorded and restorable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PositionHistorySheet(
    history: List<PositionJump>,
    onRestore: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Where you were", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tap a position to go back to it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            history.take(20).forEach { jump ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRestore(jump.fromSec) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(formatClock(jump.fromSec), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "jumped to ${formatClock(jump.toSec)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onRestore(jump.fromSec) }) { Text("Restore") }
                }
            }
        }
    }
}

/**
 * A seek button labelled with its own duration.
 *
 * Media3's stock icons are baked with "10" and "30" on them, which would lie as soon as
 * the durations became configurable. The number is drawn over a neutral icon instead.
 */
@Composable
private fun SeekIcon(seconds: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, description: String) {
    Box(contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(30.dp))
        Text(
            seconds.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSheet(
    current: Float,
    presets: List<Float>,
    onPick: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Playback speed", style = MaterialTheme.typography.titleMedium)
            Text(
                "Remembered for this title",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { speed ->
                    FilterChip(
                        selected = kotlin.math.abs(current - speed) < 0.01f,
                        onClick = { onPick(speed) },
                        label = { Text("${formatSpeedNumber(speed)}x", maxLines = 1, softWrap = false) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onPick(current - 0.05f) }) { Text("−") }
                Text(
                    "${formatSpeedNumber(current)}x",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                IconButton(onClick = { onPick(current + 0.05f) }) { Text("+") }
            }
        }
    }
}

