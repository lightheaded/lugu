package io.github.lightheaded.lugu.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.remember
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
import io.github.lightheaded.lugu.core.model.MediaType

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

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(item?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (item == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }

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
                    }
                }
            }

            item.description?.takeIf { it.isNotBlank() }?.let { description ->
                item {
                    Text(description, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (state.episodes.isNotEmpty()) {
                item {
                    Text("Episodes", style = MaterialTheme.typography.titleMedium)
                }
                items(state.episodes, key = { it.episode.id }) { row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onPlay(item.id, row.episode.id) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(row.episode.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                formatDuration(row.episode.durationSec),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (row.progressFraction > 0f) {
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { row.progressFraction },
                                    modifier = Modifier.fillMaxWidth().height(2.dp),
                                )
                            }
                        }
                        DownloadButton(
                            download = row.download,
                            onDownload = { viewModel.download(row.episode.id) },
                            onRemove = { viewModel.removeDownload(row.episode.id) },
                            compact = true,
                        )
                    }
                }
            }
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
