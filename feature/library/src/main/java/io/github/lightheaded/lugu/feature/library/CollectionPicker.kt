package io.github.lightheaded.lugu.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A list of one library's collections, in a dialog, for a choice of one of them.
 *
 * A dialog for the reason the item page gives: there can be dozens of collections, and a
 * long list in a dropdown is hard to scroll and easy to dismiss by accident.
 *
 * Two screens use it in two ways. The item page shows a tick per row ([showTicks]), stays
 * open, and treats a tap as "change this one". The grid's selection bar shows no ticks,
 * because a tick answers a question about one item and a selection is many. There a tap
 * means "put the selection here", and the caller closes the dialog in [onPick].
 *
 * Nothing in it is optimistic. The caller sends the change to the server, and the result
 * arrives as a message on the screen behind.
 */
@Composable
internal fun CollectionPickerDialog(
    collections: List<CollectionChoice>,
    onPick: (CollectionChoice) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Collections",
    showTicks: Boolean = true,
    dismissLabel: String = "Done",
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (collections.isEmpty()) {
                // This app can put a book into a collection but cannot make one, so an
                // empty list has to say where collections come from. "None" on its own
                // reads as a failure to load.
                Text(
                    NO_COLLECTIONS_LINE,
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
                            // The whole row is the target, so a tap that lands beside the
                            // box does the same thing as one that lands on it.
                            .clickable { onPick(choice) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showTicks) {
                            Checkbox(checked = choice.contains, onCheckedChange = null)
                            Spacer(Modifier.size(8.dp))
                        }
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
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
    )
}

internal const val NO_COLLECTIONS_LINE = "No collections in this library yet. They are made on the server."
