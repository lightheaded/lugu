package io.github.lightheaded.lugu.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lightheaded.lugu.core.model.formatSpeedNumber

/**
 * Everything in the player that is not transport.
 *
 * These are all occasional actions, so they sit in one dim row rather than competing with
 * the play button, and they wrap: a phone at a large font size still has to reach all of
 * them. Speed keeps a chip because it is the only one whose current value is worth
 * reading at a glance.
 *
 * This row is not configurable, and the transport row above it is. That difference is
 * deliberate: `PlayerSettings.playerButtons` decides which *transport* buttons appear,
 * because a listener who never skips a chapter wants that pair off the busiest control on
 * the screen. Every button here is always present instead, with one exception, and the
 * exception is a capability and never a preference: Audiobookshelf has no bookmark for a
 * podcast episode, so the bookmark pair is absent rather than present and refusing. A new
 * button in this row follows the same rule — always on, unless the server cannot serve it.
 *
 * Up next is the newest of them, and it is here rather than in the transport row for the
 * same reason: it opens a list, it is occasional, and both player layouts compose this row,
 * so one button reaches the queue in portrait and in landscape.
 */
@Composable
internal fun PlayerActionRow(
    speed: Float,
    sleepArmed: Boolean,
    canBookmark: Boolean,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    onAddBookmark: () -> Unit,
    onBookmarks: () -> Unit,
    onHistory: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxWidth(),
    ) {
        AssistChip(
            onClick = onSpeed,
            label = { Text("${formatSpeedNumber(speed)}x", maxLines = 1, softWrap = false) },
            leadingIcon = { Icon(Icons.Default.Speed, contentDescription = "Playback speed") },
        )

        IconButton(onClick = onSleep) {
            Icon(
                Icons.Default.Bedtime,
                contentDescription = if (sleepArmed) "Sleep timer, running" else "Sleep timer",
                tint = if (sleepArmed) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        /*
         * Books only. Audiobookshelf has no bookmark for a podcast episode, so the pair
         * is absent rather than present and refusing.
         */
        if (canBookmark) {
            IconButton(onClick = onAddBookmark) {
                Icon(
                    Icons.Default.BookmarkAdd,
                    contentDescription = "Bookmark this position",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onBookmarks) {
                Icon(
                    Icons.Default.Bookmarks,
                    contentDescription = "Bookmarks",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        IconButton(onClick = onHistory) {
            Icon(
                Icons.Default.History,
                contentDescription = "Where you were",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        /*
         * Last in the row, because the other buttons act on the item that plays now and
         * this one opens what plays after it. The icon and the words are the ones Home's
         * top bar already uses for the same screen: two names for one destination make a
         * listener check whether they are two screens.
         */
        IconButton(onClick = onOpenQueue) {
            Icon(
                Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = "Up next",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
