package io.github.lightheaded.lugu.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.lightheaded.lugu.core.model.formatClock
import io.github.lightheaded.lugu.playback.PlaybackEvents
import io.github.lightheaded.lugu.playback.PositionJump
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * When the listener listened, and where the position went.
 *
 * Began as recovery only: a notification button once seeked a book to zero and there was
 * no way back, because the database holds only *current* progress. Every large jump is
 * recorded for that. Plays and pauses are recorded too, with the date and time, because
 * "where was I when I fell asleep" is the same question asked another way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PositionHistorySheet(
    history: List<PositionJump>,
    onRestore: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val days = remember(history) { historyDays(history) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            item {
                Text("History", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Tap an entry to go back to that position",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (days.isEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Nothing yet. Plays, pauses and jumps show here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            days.forEach { day ->
                item(key = "day-${day.label}") {
                    Text(
                        day.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                }
                items(day.rows) { row -> HistoryRowView(row, onRestore) }
            }
        }
    }
}

@Composable
private fun HistoryRowView(row: HistoryRow, onRestore: (Double) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Go back to ${formatClock(row.restoreSec)}") {
                onRestore(row.restoreSec)
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.time,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp),
        )
        Icon(
            row.kind.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge)
            row.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal enum class HistoryKind(val icon: ImageVector) {
    PLAY(Icons.Default.PlayArrow),
    PAUSE(Icons.Default.Pause),
    END(Icons.Default.Stop),
    JUMP(Icons.Default.SwapVert),
}

/** One line of the history: when, what, and the position a tap goes back to. */
internal data class HistoryRow(
    val time: String,
    val kind: HistoryKind,
    val title: String,
    val detail: String?,
    val restoreSec: Double,
)

internal data class HistoryDay(val label: String, val rows: List<HistoryRow>)

/** The history, newest first, in one group per calendar day. */
internal fun historyDays(
    history: List<PositionJump>,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): List<HistoryDay> =
    history
        .sortedByDescending { it.atMs }
        .groupBy { Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() }
        .map { (date, entries) -> HistoryDay(dayLabel(date, today), entries.map { historyRow(it, zone) }) }

internal fun historyRow(jump: PositionJump, zone: ZoneId = ZoneId.systemDefault()): HistoryRow {
    val time = Instant.ofEpochMilli(jump.atMs).atZone(zone).format(TIME)
    val at = formatClock(jump.toSec)
    return when (jump.reason) {
        PlaybackEvents.PLAY -> HistoryRow(time, HistoryKind.PLAY, "Played from $at", null, jump.toSec)
        PlaybackEvents.PAUSE -> HistoryRow(time, HistoryKind.PAUSE, "Paused at $at", null, jump.toSec)
        PlaybackEvents.PAUSE_NOISY ->
            HistoryRow(time, HistoryKind.PAUSE, "Paused at $at", "Headphones disconnected", jump.toSec)
        PlaybackEvents.PAUSE_FOCUS ->
            HistoryRow(time, HistoryKind.PAUSE, "Paused at $at", "Another app took the audio", jump.toSec)
        PlaybackEvents.PAUSE_SLEEP_TIMER ->
            HistoryRow(time, HistoryKind.PAUSE, "Paused at $at", "Sleep timer", jump.toSec)
        PlaybackEvents.ENDED -> HistoryRow(time, HistoryKind.END, "Reached the end at $at", null, jump.toSec)
        else -> HistoryRow(
            time = time,
            kind = HistoryKind.JUMP,
            title = "${formatClock(jump.fromSec)} → $at",
            detail = jumpLabel(jump.reason),
            restoreSec = jump.fromSec,
        )
    }
}

/** The stored seek reasons of `SeekReasons`, in words. */
private fun jumpLabel(reason: String): String = when (reason) {
    "seek" -> "Seek"
    "skip-back" -> "Skip back"
    "skip-forward" -> "Skip forward"
    "chapter-previous" -> "Previous chapter"
    "chapter-next" -> "Next chapter"
    "skipped-region" -> "Skipped a trimmed part"
    "rewind-on-resume" -> "Rewind on resume"
    "restore" -> "Restored from history"
    else -> reason
}

internal fun dayLabel(date: LocalDate, today: LocalDate): String = when {
    date == today -> "Today"
    date == today.minusDays(1) -> "Yesterday"
    date.year == today.year -> date.format(SAME_YEAR)
    else -> date.format(OTHER_YEAR)
}

// British ordering and a 24-hour clock, fixed like the dates on the item screen: the rest
// of the app is written in one language.
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val SAME_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
private val OTHER_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.UK)
