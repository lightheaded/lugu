package io.github.lightheaded.lugu.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.lightheaded.lugu.core.model.SleepMode
import io.github.lightheaded.lugu.playback.PositionJump

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val jump by viewModel.pendingJump.collectAsStateWithLifecycle()
    val rewindNotice by viewModel.rewindNotice.collectAsStateWithLifecycle()
    val sleep by viewModel.sleepTimer.collectAsStateWithLifecycle()
    var showSleepSheet by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }
    val history by viewModel.positionHistory.collectAsStateWithLifecycle(initialValue = emptyList())

    var scrubbing by remember { mutableStateOf<Float?>(null) }

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
            onPick = {
                viewModel.setSleepTimer(it)
                showSleepSheet = false
            },
            onDismiss = { showSleepSheet = false },
        )
    }

    Scaffold(
        modifier = modifier,
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
            /*
             * A position adopted from another device is announced, never silent. An
             * automatic correction the user cannot see is indistinguishable from the
             * app losing their place — which is the complaint lugu exists to answer.
             */
            jump?.let { pending ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Jumped from ${formatTime(pending.fromSec)} to ${formatTime(pending.toSec)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::undoJump) { Text("Undo") }
                    TextButton(onClick = viewModel::dismissJump) { Text("Keep") }
                }
            }

            Spacer(Modifier.height(16.dp))
            AsyncImage(
                model = nowPlaying?.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )

            Spacer(Modifier.height(24.dp))
            Text(
                nowPlaying?.title ?: "Nothing playing",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            nowPlaying?.author?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The smart rewind announces itself and then fades out of the way.
            rewindNotice?.let { notice ->
                LaunchedEffect(notice) {
                    kotlinx.coroutines.delay(4_000)
                    viewModel.dismissRewindNotice()
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    notice,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            state.chapter?.let { chapter ->
                Spacer(Modifier.height(8.dp))
                Text(
                    chapter.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.chapterCount > 1) {
                    Text(
                        "Chapter ${state.chapterIndex + 1} of ${state.chapterCount} · " +
                            "${formatTime(state.chapterPositionSec)} / ${formatTime(state.chapterDurationSec)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    formatTime(scrubbing?.toDouble() ?: state.positionSec),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    "-${formatTime(state.durationSec - (scrubbing?.toDouble() ?: state.positionSec))}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Spacer(Modifier.height(24.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconButton(
                    onClick = viewModel::previousChapter,
                    enabled = state.chapterCount > 1,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous chapter")
                }
                IconButton(onClick = { viewModel.seekBy(-10.0) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.Replay10, contentDescription = "Back 10 seconds")
                }
                FilledIconButton(
                    onClick = viewModel::togglePlayPause,
                    modifier = Modifier.size(72.dp),
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = { viewModel.seekBy(30.0) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.Forward30, contentDescription = "Forward 30 seconds")
                }
                IconButton(
                    onClick = viewModel::nextChapter,
                    enabled = state.chapterCount > 1,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next chapter")
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                viewModel.speedPresets.forEach { speed ->
                    FilterChip(
                        selected = kotlin.math.abs(state.speed - speed) < 0.01f,
                        onClick = { viewModel.setSpeed(speed) },
                        label = { Text("${speed}x") },
                    )
                }
            }
            Text(
                "Speed is remembered for this book",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            if (sleep.isArmed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Bedtime,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        sleep.remainingSec?.let { "Sleeping in ${formatTime(it)}" } ?: "Sleep timer on",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    TextButton(onClick = { viewModel.extendSleepTimer(5) }) { Text("+5 min") }
                    TextButton(onClick = { viewModel.setSleepTimer(null) }) { Text("Cancel") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showSleepSheet = true }) {
                        Icon(Icons.Default.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Sleep timer")
                    }
                    if (history.isNotEmpty()) {
                        TextButton(onClick = { showHistorySheet = true }) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("History")
                        }
                    }
                }
            }

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

    Column(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
        LinearProgressIndicator(
            progress = {
                if (state.durationSec > 0) (state.positionSec / state.durationSec).toFloat() else 0f
            },
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
                model = current.coverUrl,
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
                    current.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.chapter?.title ?: current.author.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = viewModel::togglePlayPause) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                )
            }
        }
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
                        Text(formatTime(jump.fromSec), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "jumped to ${formatTime(jump.toSec)}",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    hasChapters: Boolean,
    presets: List<Int>,
    onPick: (SleepMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Sleep timer", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { minutes ->
                    AssistChip(
                        onClick = { onPick(SleepMode.Duration(minutes)) },
                        label = { Text("$minutes min") },
                    )
                }
            }
            if (hasChapters) {
                Spacer(Modifier.height(12.dp))
                AssistChip(
                    onClick = { onPick(SleepMode.EndOfChapter) },
                    label = { Text("End of chapter") },
                )
                Text(
                    "Follows you if you skip a chapter",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun formatTime(seconds: Double): String {
    val safe = seconds.coerceAtLeast(0.0).toLong()
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val secs = safe % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}
