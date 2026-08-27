package io.github.lightheaded.lugu.feature.player

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import io.github.lightheaded.lugu.core.model.SleepTimerState
import io.github.lightheaded.lugu.core.model.formatClock
import io.github.lightheaded.lugu.core.model.formatLength
import io.github.lightheaded.lugu.core.model.formatSpeedNumber
import io.github.lightheaded.lugu.core.sync.PlayerSettings
import io.github.lightheaded.lugu.core.sync.SpeedSettings
import io.github.lightheaded.lugu.core.sync.TransportButton
import io.github.lightheaded.lugu.core.ui.Status
import io.github.lightheaded.lugu.core.ui.StatusStrip
import io.github.lightheaded.lugu.playback.NowPlaying
import io.github.lightheaded.lugu.playback.PlayerUiState
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

    // The transcoding notice is true for as long as the stream is, so it does not take
    // itself away — but a fact that has been read once does not need to keep the top of
    // the screen. Keyed on the item, because the next book is a different fact and the
    // person who put this one away has not been asked about it.
    var transcodingNoticeGone by remember(nowPlaying?.libraryItemId) { mutableStateOf(false) }

    /*
     * Both notices are overlays and neither is inline content, so announcing a
     * correction never reflows the screen. An inline banner made the cover art and the
     * whole transport shift down as it appeared and back up as it went — which is a
     * worse interruption than the thing being announced. The playback error and the
     * transcoding notice were the two that had stayed inline; they are overlays now too,
     * drawn by the strip at the top rather than as snackbars, because neither of them is
     * a passing event with an action attached. See [StatusStrip] for the whole rule.
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

    val addBookmark: () -> Unit = {
        viewModel.addBookmark()
        // The write lands in Room before the server hears about it, so the
        // confirmation is honest even with no signal.
        scope.launch {
            snackbarHostState.showSnackbar(
                "Bookmarked at ${formatClock(state.positionSec)}",
                withDismissAction = true,
            )
        }
    }

    // Landscape gets its own arrangement so the transport is never clipped, but every
    // sheet, snackbar and the status strip stay wired at this level and so work the same
    // in both: only which composables draw the cover, the seek bar and the transport
    // changes below, never how a chapter, the sleep timer, the speed sheet or a notice
    // is reached.
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

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
        // A Box, so the two things the player has to say are drawn over the top of the
        // screen and take no layout space at all. See [StatusStrip] for the rule.
        Box(Modifier.fillMaxSize()) {
            if (isLandscape) {
                LandscapePlayerContent(
                    padding = padding,
                    nowPlaying = nowPlaying,
                    state = state,
                    settings = settings,
                    sleep = sleep,
                    canBookmark = canBookmark,
                    scrubbing = scrubbing,
                    onScrub = { scrubbing = it },
                    onScrubFinished = {
                        scrubbing?.let { viewModel.seekTo(it.toDouble()) }
                        scrubbing = null
                    },
                    onOpenItem = onOpenItem,
                    onOpenChapters = { showChapterSheet = true },
                    viewModel = viewModel,
                    onSpeed = { showSpeedSheet = true },
                    onSleep = { showSleepSheet = true },
                    onAddBookmark = addBookmark,
                    onBookmarks = { showBookmarkSheet = true },
                    onHistory = { showHistorySheet = true },
                )
            } else {
                PortraitPlayerContent(
                    padding = padding,
                    nowPlaying = nowPlaying,
                    state = state,
                    settings = settings,
                    sleep = sleep,
                    canBookmark = canBookmark,
                    scrubbing = scrubbing,
                    onScrub = { scrubbing = it },
                    onScrubFinished = {
                        scrubbing?.let { viewModel.seekTo(it.toDouble()) }
                        scrubbing = null
                    },
                    onOpenItem = onOpenItem,
                    onOpenChapters = { showChapterSheet = true },
                    viewModel = viewModel,
                    onSpeed = { showSpeedSheet = true },
                    onSleep = { showSleepSheet = true },
                    onAddBookmark = addBookmark,
                    onBookmarks = { showBookmarkSheet = true },
                    onHistory = { showHistorySheet = true },
                )
            }

            StatusStrip(
                status = playerStatus(
                    error = state.error,
                    transcoded = nowPlaying?.isTranscoded == true,
                    noticeGone = transcodingNoticeGone,
                ),
                onDismiss = {
                    if (state.error != null) {
                        viewModel.dismissError()
                    } else {
                        transcodingNoticeGone = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = padding.calculateTopPadding()),
            )
        }
    }
}

/**
 * The portrait layout, unchanged from before the landscape layout existed: cover, title,
 * chapter readout, seek bar and transport in one centred column. Kept as one function, with
 * the same modifiers and the same order, so the committed screenshot baselines still match.
 */
@Composable
private fun PortraitPlayerContent(
    padding: PaddingValues,
    nowPlaying: NowPlaying?,
    state: PlayerUiState,
    settings: PlayerSettings,
    sleep: SleepTimerState,
    canBookmark: Boolean,
    scrubbing: Float?,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    onOpenItem: (String) -> Unit,
    onOpenChapters: () -> Unit,
    viewModel: PlayerViewModel,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    onAddBookmark: () -> Unit,
    onBookmarks: () -> Unit,
    onHistory: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        CoverArt(
            coverUrl = nowPlaying?.coverUrl,
            title = nowPlaying?.title,
            enabled = nowPlaying != null,
            onOpen = { nowPlaying?.libraryItemId?.let(onOpenItem) },
            modifier = Modifier.fillMaxWidth(0.8f).aspectRatio(1f),
        )

        Spacer(Modifier.height(24.dp))
        TitleAndAuthor(
            title = nowPlaying?.title ?: "Nothing playing",
            author = nowPlaying?.author,
            enabled = nowPlaying != null,
            onOpen = { nowPlaying?.libraryItemId?.let(onOpenItem) },
        )

        state.chapter?.let { chapter ->
            /*
             * The readout is the way into the chapter list, because it is already
             * what someone is looking at when they wonder where they are. An item
             * with no chapters to choose between keeps the readout and loses the
             * tap: an affordance that opens an empty sheet is worse than none.
             */
            Spacer(Modifier.height(8.dp))
            ChapterReadout(
                title = chapter.title,
                hasChapterList = state.chapterCount > 1,
                chapterIndex = state.chapterIndex,
                chapterCount = state.chapterCount,
                chapterPositionSec = state.chapterPositionSec,
                chapterDurationSec = state.chapterDurationSec,
                onOpen = onOpenChapters,
            )
        }

        Spacer(Modifier.height(24.dp))
        SeekBar(
            scrubbing = scrubbing,
            positionSec = state.positionSec,
            durationSec = state.durationSec,
            onScrub = onScrub,
            onScrubFinished = onScrubFinished,
        )

        Spacer(Modifier.height(24.dp))
        /*
         * Laid out by how often each control is actually used: seeking back to
         * re-hear a missed sentence dominates, finding a place is next, and chapter
         * skipping is occasional. So the seek pair flanks play/pause at full size
         * and the chapter pair sits outside it, smaller and dimmer.
         */
        TransportRow(
            isPlaying = state.isPlaying,
            chapterCount = state.chapterCount,
            playerButtons = settings.playerButtons,
            skipBackSec = settings.skipBackSec,
            skipForwardSec = settings.skipForwardSec,
            onPreviousChapter = viewModel::previousChapter,
            onNextChapter = viewModel::nextChapter,
            onSeekBack = { viewModel.seekBy(-settings.skipBackSec.toDouble()) },
            onSeekForward = { viewModel.seekBy(settings.skipForwardSec.toDouble()) },
            onTogglePlayPause = viewModel::togglePlayPause,
        )

        Spacer(Modifier.height(16.dp))
        PlayerActionRow(
            speed = state.speed,
            sleepArmed = sleep.isArmed,
            canBookmark = canBookmark,
            onSpeed = onSpeed,
            onSleep = onSleep,
            onAddBookmark = onAddBookmark,
            onBookmarks = onBookmarks,
            onHistory = onHistory,
        )

        Spacer(Modifier.height(8.dp))
        // The playback error and the transcoding notice used to end this column, and
        // both of them moved it: the column is centred, so a line added at the bottom
        // lifted the cover art, the title and the whole transport. They are drawn by
        // the strip over the top of the screen instead, which is what the rewind and
        // jump notices above already do.
    }
}

/**
 * The landscape layout: cover and titles on one side, seek bar and transport on the
 * other, so the transport is never clipped off the bottom of a phone turned sideways.
 *
 * Every sheet (chapters, sleep timer, speed, bookmarks, position history) and every
 * notice (rewind, jump, continuation, the trim notice, the transcoding status) is wired
 * at the [PlayerScreen] level and drawn as an overlay or a bottom sheet, so all of them
 * stay reachable here exactly as they do in portrait — this function only rearranges the
 * cover, the titles, the seek bar and the transport.
 */
@Composable
private fun LandscapePlayerContent(
    padding: PaddingValues,
    nowPlaying: NowPlaying?,
    state: PlayerUiState,
    settings: PlayerSettings,
    sleep: SleepTimerState,
    canBookmark: Boolean,
    scrubbing: Float?,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    onOpenItem: (String) -> Unit,
    onOpenChapters: () -> Unit,
    viewModel: PlayerViewModel,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    onAddBookmark: () -> Unit,
    onBookmarks: () -> Unit,
    onHistory: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CoverArt(
                coverUrl = nowPlaying?.coverUrl,
                title = nowPlaying?.title,
                enabled = nowPlaying != null,
                onOpen = { nowPlaying?.libraryItemId?.let(onOpenItem) },
                // Bounded by the row's height rather than by width, because width is the
                // scarce dimension in landscape and height is what a phone on its side
                // still has plenty of.
                modifier = Modifier.fillMaxHeight(0.75f).aspectRatio(1f, matchHeightConstraintsFirst = true),
            )
            Spacer(Modifier.height(8.dp))
            TitleAndAuthor(
                title = nowPlaying?.title ?: "Nothing playing",
                author = nowPlaying?.author,
                enabled = nowPlaying != null,
                onOpen = { nowPlaying?.libraryItemId?.let(onOpenItem) },
            )
        }

        Spacer(Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            state.chapter?.let { chapter ->
                ChapterReadout(
                    title = chapter.title,
                    hasChapterList = state.chapterCount > 1,
                    chapterIndex = state.chapterIndex,
                    chapterCount = state.chapterCount,
                    chapterPositionSec = state.chapterPositionSec,
                    chapterDurationSec = state.chapterDurationSec,
                    onOpen = onOpenChapters,
                )
                Spacer(Modifier.height(4.dp))
            }

            SeekBar(
                scrubbing = scrubbing,
                positionSec = state.positionSec,
                durationSec = state.durationSec,
                onScrub = onScrub,
                onScrubFinished = onScrubFinished,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            TransportRow(
                isPlaying = state.isPlaying,
                chapterCount = state.chapterCount,
                playerButtons = settings.playerButtons,
                skipBackSec = settings.skipBackSec,
                skipForwardSec = settings.skipForwardSec,
                onPreviousChapter = viewModel::previousChapter,
                onNextChapter = viewModel::nextChapter,
                onSeekBack = { viewModel.seekBy(-settings.skipBackSec.toDouble()) },
                onSeekForward = { viewModel.seekBy(settings.skipForwardSec.toDouble()) },
                onTogglePlayPause = viewModel::togglePlayPause,
            )

            Spacer(Modifier.height(4.dp))
            PlayerActionRow(
                speed = state.speed,
                sleepArmed = sleep.isArmed,
                canBookmark = canBookmark,
                onSpeed = onSpeed,
                onSleep = onSleep,
                onAddBookmark = onAddBookmark,
                onBookmarks = onBookmarks,
                onHistory = onHistory,
            )
        }
    }
}

/** The cover art. The biggest thing on the screen, and it looks like the book, so it goes
 * where the book does. Clipped first, so the ripple follows the corners rather than the
 * square behind them. [modifier] carries the size, which differs between portrait and
 * landscape. */
@Composable
private fun CoverArt(
    coverUrl: String?,
    title: String?,
    enabled: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = coverUrl,
        contentDescription = title,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onOpen),
    )
}

/** The title is a link to the item wherever it is shown. */
@Composable
private fun TitleAndAuthor(
    title: String,
    author: String?,
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable(enabled = enabled, onClick = onOpen),
    )
    author?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The current chapter, and the way into the chapter list. */
@Composable
private fun ChapterReadout(
    title: String,
    hasChapterList: Boolean,
    chapterIndex: Int,
    chapterCount: Int,
    chapterPositionSec: Double,
    chapterDurationSec: Double,
    onOpen: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = hasChapterList, onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hasChapterList) {
            Text(
                "Chapter ${chapterIndex + 1} of $chapterCount · " +
                    "${formatClock(chapterPositionSec)} / " +
                    formatClock(chapterDurationSec),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The seek slider and the elapsed/remaining readout beside it. */
@Composable
private fun SeekBar(
    scrubbing: Float?,
    positionSec: Double,
    durationSec: Double,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = scrubbing ?: positionSec.toFloat(),
        onValueChange = onScrub,
        onValueChangeFinished = onScrubFinished,
        valueRange = 0f..(durationSec.toFloat().coerceAtLeast(1f)),
        modifier = modifier.semantics {
            contentDescription = "Playback position"
            // Without this a Slider announces a percentage, which on a forty-hour
            // book is the least useful number available: "43 percent" is four
            // hours wide. The two figures either side of the bar are what a
            // sighted listener reads, so they are what this says.
            stateDescription = "${formatLength(scrubbing?.toDouble() ?: positionSec)} " +
                "of ${formatLength(durationSec)}"
        },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            formatClock(scrubbing?.toDouble() ?: positionSec),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            "-${formatClock(durationSec - (scrubbing?.toDouble() ?: positionSec))}",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** Previous chapter, seek back, play/pause, seek forward, next chapter. */
@Composable
private fun TransportRow(
    isPlaying: Boolean,
    chapterCount: Int,
    playerButtons: Set<TransportButton>,
    skipBackSec: Int,
    skipForwardSec: Int,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (TransportButton.PREVIOUS_CHAPTER in playerButtons) {
            IconButton(
                onClick = onPreviousChapter,
                enabled = chapterCount > 1,
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
            onClick = onSeekBack,
            modifier = Modifier.size(60.dp),
        ) {
            SeekIcon(
                seconds = skipBackSec,
                icon = Icons.Default.Replay10,
                description = "Back $skipBackSec seconds",
            )
        }

        FilledIconButton(
            onClick = onTogglePlayPause,
            modifier = Modifier.size(76.dp),
        ) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(38.dp),
            )
        }

        FilledTonalIconButton(
            onClick = onSeekForward,
            modifier = Modifier.size(60.dp),
        ) {
            SeekIcon(
                seconds = skipForwardSec,
                icon = Icons.Default.Forward30,
                description = "Forward $skipForwardSec seconds",
            )
        }

        if (TransportButton.NEXT_CHAPTER in playerButtons) {
            IconButton(
                onClick = onNextChapter,
                enabled = chapterCount > 1,
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
}

/**
 * The one thing worth saying about playback right now.
 *
 * A failure outranks the transcoding notice: a stream that will not play at all makes the
 * precision of a seek beside the point.
 */
private fun playerStatus(error: String?, transcoded: Boolean, noticeGone: Boolean): Status? = when {
    error != null -> Status.Problem(error)
    transcoded && !noticeGone -> Status.Note("Transcoding — seeking is less precise")
    else -> null
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
            // The fine adjustment. It steps on a grid and stops at the ends rather than
            // accepting presses that change nothing: a button that responds to nothing is
            // read as the sheet having stopped working, not as a limit having been reached.
            Row(verticalAlignment = Alignment.CenterVertically) {
                val slower = SpeedSettings.stepped(current, -1)
                val faster = SpeedSettings.stepped(current, 1)
                IconButton(
                    onClick = { onPick(slower) },
                    enabled = slower < current,
                    modifier = Modifier.semantics { contentDescription = "Slower" },
                ) {
                    Text("−")
                }
                Text(
                    "${formatSpeedNumber(current)}x",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .semantics { contentDescription = "Speed ${formatSpeedNumber(current)} times" },
                )
                IconButton(
                    onClick = { onPick(faster) },
                    enabled = faster > current,
                    modifier = Modifier.semantics { contentDescription = "Faster" },
                ) {
                    Text("+")
                }
            }
        }
    }
}

