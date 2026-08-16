package io.github.lightheaded.lugu.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lightheaded.lugu.core.model.Bookmark
import io.github.lightheaded.lugu.core.model.formatClock
import io.github.lightheaded.lugu.core.model.formatSpeedNumber

/**
 * The places worth coming back to.
 *
 * Bookmarks belong to the server and to the account, not to this phone, so the list is
 * read from Room and works with no signal — and one made offline is shown straight away,
 * marked, rather than being held back until it has been accepted. A bookmark that only
 * appears once there is a network is a bookmark nobody trusts.
 *
 * Times are the book's own seconds, matching the scrubber and the chapter list. A
 * listener at 1.5x reaches 1:00:00 of a book after forty minutes of their evening, and
 * both figures are true — but only one of them is where the audio is, and only one of
 * them is stable when the speed changes. So the audio position is the figure, and the
 * wall-clock estimate appears underneath it, labelled with the speed it assumes, and only
 * when that speed is not 1x and would therefore surprise someone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookmarkSheet(
    bookmarks: List<Bookmark>,
    speed: Float,
    onSeek: (Double) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf<Bookmark?>(null) }

    renaming?.let { bookmark ->
        RenameBookmarkDialog(
            bookmark = bookmark,
            onConfirm = { title ->
                onRename(bookmark.timeSec, title)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("Bookmarks", style = MaterialTheme.typography.titleMedium)
            Text(
                if (bookmarks.isEmpty()) {
                    "Nothing marked yet. The bookmark button saves wherever you are, and the " +
                        "same bookmarks show up on the server and in every other client."
                } else {
                    "Tap a bookmark to go there"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(bookmarks, key = { it.timeSec }) { bookmark ->
                BookmarkRow(
                    bookmark = bookmark,
                    speed = speed,
                    onSeek = onSeek,
                    onRename = { renaming = bookmark },
                    onDelete = { onDelete(bookmark.timeSec) },
                )
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    speed: Float,
    onSeek: (Double) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val audioSec = bookmark.timeSec.toDouble()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSeek(audioSec) }
            .padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                bookmark.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatClock(audioSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (kotlin.math.abs(speed - 1f) > 0.01f) {
                    Text(
                        "about ${formatClock(wallClockSecondsAt(audioSec, speed))} of " +
                            "listening at ${formatSpeedNumber(speed)}x",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (bookmark.isPending) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.height(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Saved here, not on the server yet",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        IconButton(onClick = onRename) {
            Icon(Icons.Default.Edit, contentDescription = "Rename bookmark")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete bookmark")
        }
    }
}

/**
 * Renaming, in a dialog rather than in place.
 *
 * The row is a seek target first: an editable field inside it would put a keyboard in
 * the way of the tap that people actually came for.
 */
@Composable
private fun RenameBookmarkDialog(
    bookmark: Bookmark,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(bookmark.timeSec) { mutableStateOf(bookmark.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename bookmark") },
        text = {
            Column {
                Text(
                    formatClock(bookmark.timeSec.toDouble()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
            }
        },
        // An empty name is left to the repository, which names a bookmark after its
        // position. Clearing the field is a reasonable way to ask for that back.
        confirmButton = { TextButton(onClick = { onConfirm(title) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
