package io.github.lightheaded.lugu.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lightheaded.lugu.core.model.EpisodeSort
import io.github.lightheaded.lugu.core.model.ListFilter
import io.github.lightheaded.lugu.core.ui.Status
import io.github.lightheaded.lugu.core.ui.StatusStrip
import kotlinx.coroutines.delay

/**
 * The episodes of every podcast in the library, in one list.
 *
 * The controls are the ones of one podcast's page, so the list sorts and filters the same
 * way. Each row names its podcast above the episode title, because here the rows come from
 * many shows.
 *
 * The status strip sits in the [Box] that wraps the list, below the search and the chips,
 * never over them. See the Compose overlay rule in `CLAUDE.md`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllEpisodesScreen(
    onBack: () -> Unit,
    onPlay: (itemId: String, episodeId: String) -> Unit,
    onOpenEpisode: (itemId: String, episodeId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AllEpisodesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // A confirmation says what it did and then stops. A note that stays reads as a state the
    // screen is in.
    LaunchedEffect(state.status) {
        if (state.status is Status.Done) {
            delay(NOTE_MS)
            viewModel.dismissStatus()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Episodes", maxLines = 1)
                        if (state.libraryName.isNotBlank()) {
                            Text(
                                state.libraryName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ListControlsBar(
                query = state.query,
                onQueryChange = viewModel::setQuery,
                searchPlaceholder = "Search episodes",
                searchDescription = "Search by episode title or podcast",
                sortOptions = EpisodeSort.entries.map { SortOption(it.id, it.label) },
                selectedSortId = state.sort.id,
                onSortSelected = { viewModel.setSort(EpisodeSort.fromId(it)) },
                filters = ListFilter.entries,
                selectedFilter = state.filter,
                onFilterSelected = viewModel::setFilter,
            )
            if (state.episodeCount > 0) {
                Text(
                    episodeCountLine(state.episodes.size, state.episodeCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (state.episodes.isEmpty()) {
                    Text(
                        emptyLine(state),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.episodes, key = { it.key }) { entry ->
                            EpisodeRowView(
                                row = entry.row,
                                podcastTitle = entry.podcastTitle,
                                // No selection here yet. A long press does nothing rather
                                // than open a mode with no bar to act on it.
                                selectionActive = false,
                                isSelected = false,
                                onOpen = { onOpenEpisode(entry.itemId, entry.episodeId) },
                                onPlay = { onPlay(entry.itemId, entry.episodeId) },
                                onToggle = {},
                                onDownload = { viewModel.download(entry) },
                                onRemoveDownload = { viewModel.removeDownload(entry) },
                                onPlayNext = { viewModel.playNext(entry) },
                                onAddToQueue = { viewModel.addToQueue(entry) },
                                onSetFinished = { viewModel.setFinished(entry, it) },
                            )
                        }
                    }
                }

                StatusStrip(
                    status = state.status,
                    onDismiss = viewModel::dismissStatus,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

/** Why the list is empty: the search, the filter, the first fetch, or no episodes at all. */
private fun emptyLine(state: AllEpisodesUiState): String = when {
    !state.loaded -> "Loading…"
    state.query.isNotBlank() -> "No episodes match “${state.query}”."
    state.filter != ListFilter.ALL && state.episodeCount > 0 -> "No episodes match “${state.filter.label}”."
    state.status is Status.Working -> "Fetching episodes…"
    else -> "No episodes in this library yet."
}

/** Long enough to read a short sentence, short enough not to become part of the screen. */
private const val NOTE_MS = 4_000L
