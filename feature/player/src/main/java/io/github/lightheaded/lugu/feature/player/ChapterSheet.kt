package io.github.lightheaded.lugu.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lightheaded.lugu.core.model.Chapter
import io.github.lightheaded.lugu.core.model.Chapters

/** A chapter as the list draws it: its number, whether it is the one playing, and how far in. */
internal data class ChapterRow(
    val chapter: Chapter,
    val number: Int,
    val isCurrent: Boolean,
    /** Fraction of this chapter already played, and null for every other chapter. */
    val progress: Float?,
)

/**
 * Turns the chapter list and a position into rows.
 *
 * Kept apart from the composable because "which chapter is this" is the one thing in the
 * sheet that can be wrong in a way nobody notices — a boundary off by a chapter marks the
 * wrong row and scrolls to the wrong place — and it is worth a test.
 */
internal fun chapterRows(chapters: List<Chapter>, positionSec: Double): List<ChapterRow> {
    val currentIndex = Chapters.indexAt(chapters, positionSec)
    return chapters.mapIndexed { index, chapter ->
        val length = chapter.endSec - chapter.startSec
        ChapterRow(
            chapter = chapter,
            number = index + 1,
            isCurrent = index == currentIndex,
            progress = if (index != currentIndex) {
                null
            } else if (length > 0.0) {
                ((positionSec - chapter.startSec) / length).coerceIn(0.0, 1.0).toFloat()
            } else {
                // A zero-length chapter is a bad chapter list, not a chapter that has not
                // started; showing it as complete is the less confusing of the two lies.
                1f
            },
        )
    }
}

/**
 * The whole book at a glance, and a way into any part of it.
 *
 * Opened from the chapter readout rather than from a button of its own: the readout is
 * already what someone looks at when they are wondering where they are, so it is where
 * they will press when they want to be somewhere else.
 *
 * The list opens at the current chapter. A book with sixty chapters that opens at the top
 * makes the listener scroll to find themselves before they can do anything, which is the
 * failing that makes chapter lists go unused.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChapterSheet(
    chapters: List<Chapter>,
    positionSec: Double,
    onSeek: (Double) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = chapterRows(chapters, positionSec)
    val listState = rememberLazyListState()
    val currentIndex = rows.indexOfFirst { it.isCurrent }

    // Only on opening. Following the position while the sheet is up would drag the list
    // out from under a finger that is reaching for another chapter.
    LaunchedEffect(Unit) {
        if (currentIndex > 0) listState.scrollToItem((currentIndex - 2).coerceAtLeast(0))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("Chapters", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tap a chapter to go there",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(state = listState) {
            items(rows, key = { it.chapter.id }) { row ->
                ChapterRowItem(row = row, onSeek = onSeek)
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun ChapterRowItem(row: ChapterRow, onSeek: (Double) -> Unit) {
    val colour = if (row.isCurrent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSeek(row.chapter.startSec) }
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.number.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                row.chapter.title,
                style = MaterialTheme.typography.bodyLarge,
                color = colour,
                fontWeight = if (row.isCurrent) FontWeight.SemiBold else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    formatTime(row.chapter.startSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatDurationLabel(row.chapter.endSec - row.chapter.startSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            row.progress?.let { progress ->
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            }
        }
        if (row.isCurrent) {
            Spacer(Modifier.width(12.dp))
            Text(
                "Playing",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
