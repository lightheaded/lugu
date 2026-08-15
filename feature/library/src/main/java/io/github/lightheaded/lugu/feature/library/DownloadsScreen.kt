package io.github.lightheaded.lugu.feature.library

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lightheaded.lugu.core.download.DownloadStatus

/**
 * What is on the phone, and how much room it is taking.
 *
 * The storage readout is at the top rather than buried in settings because the cap is
 * the thing that will eventually refuse a download, and someone who hits it should be
 * able to see why and fix it in the same place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state.downloads.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Nothing downloaded yet.\n\nDownload a book from its page and it will play " +
                        "with no connection at all.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                    Text(
                        "${formatBytes(state.bytesUsed)} of ${formatBytes(state.settings.storageCapBytes)}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.fractionOfCap },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${state.complete.size} ready to play offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }

            items(state.downloads, key = { "${it.libraryItemId}#${it.episodeId.orEmpty()}" }) { download ->
                DownloadRowView(
                    download = download,
                    onOpen = { onOpenItem(download.libraryItemId) },
                    onRemove = { viewModel.remove(download) },
                    onRetry = { viewModel.retry(download) },
                )
            }
        }
    }
}

@Composable
private fun DownloadRowView(
    download: DownloadStatus,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                download.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            download.author?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                download.isComplete -> Text(
                    formatBytes(download.bytesDownloaded),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                download.isFailed -> Text(
                    download.error ?: "Download failed — tap the arrow to try again",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { download.percent.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                    )
                }
            }
        }
        DownloadButton(
            download = download,
            onDownload = onRetry,
            onRemove = onRemove,
            compact = true,
        )
    }
}

/** Binary units, because that is what a phone's storage screen shows. */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val gb = bytes / (1024.0 * 1024 * 1024)
    if (gb >= 1) return "%.1f GB".format(gb)
    val mb = bytes / (1024.0 * 1024)
    if (mb >= 1) return "%.0f MB".format(mb)
    return "%.0f KB".format(bytes / 1024.0)
}
