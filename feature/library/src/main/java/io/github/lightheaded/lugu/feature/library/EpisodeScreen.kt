package io.github.lightheaded.lugu.feature.library

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lightheaded.lugu.core.model.PodcastEpisode
import io.github.lightheaded.lugu.core.model.formatLengthCompact
import io.github.lightheaded.lugu.core.model.parseShowNotes

/**
 * One episode: what it is about, and the two things worth doing about it.
 *
 * This page exists because an episode's own description was already on the phone and had
 * never been drawn. The item sync mirrors it with everything else, so the show notes for
 * every episode of every followed podcast were sitting in Room, unread — and "shall I hear
 * this one" is a question a title and a date cannot answer.
 *
 * It is deliberately short. Everything on it either says what the episode is (the title,
 * the date, the length, the notes) or acts on that decision (play, download, queue, mark),
 * and nothing else earns the room: an episode page that has to be scrolled past to reach
 * the play button has made the decision harder rather than easier.
 *
 * ### Where it sits in the back stack
 *
 * Always on top of Home, never as the start destination — which is the same answer the item
 * page gives, and it is given the same way. A tap on the new-episode notification starts
 * `MainActivity`, which sets up the graph with Home at its root and then navigates here, so
 * back leads to the library whether the app was already running or had never been started.
 * The failure this avoids is a cold notification tap that lands on a page with nothing
 * underneath it, where back closes the app rather than showing what else is there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeScreen(
    onBack: () -> Unit,
    onPlay: (itemId: String, episodeId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EpisodeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
                // The show, not the episode. The episode's own title is the first line of
                // the page underneath and is far too long for a bar; what a reader needs
                // from the bar is which feed this arrived in.
                title = { Text(state.showTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val episode = state.episode
        if (episode == null) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (state.loaded) {
                        // A notification can outlive the episode it names — a feed can
                        // withdraw one, and a re-mirror then takes the row away underneath
                        // the tap. Saying so is better than an empty page.
                        "This episode is no longer in the feed."
                    } else {
                        "Loading…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        EpisodeDetail(
            state = state,
            episode = episode,
            onPlay = { onPlay(episode.libraryItemId, episode.id) },
            onDownload = viewModel::download,
            onRemoveDownload = viewModel::removeDownload,
            onPlayNext = viewModel::playNext,
            onAddToQueue = viewModel::addToQueue,
            onSetFinished = viewModel::setFinished,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

/**
 * The page's own body, drawn from state rather than from a view model.
 *
 * Split out so that the screenshot test can photograph it: every screen in lugu takes a
 * Hilt view model over Room and DataStore, and none of them can be rendered from
 * fabricated state as they stand.
 */
@Composable
internal fun EpisodeDetail(
    state: EpisodeUiState,
    episode: PodcastEpisode,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onSetFinished: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val subline = remember(episode) { episodeSubline(episode) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(episode.title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    subline,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                        "${formatLengthCompact(state.positionSec)} of " +
                            formatLengthCompact(episode.durationSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // The same row of controls the item page gives a book, in the same order. The
        // request was to decide from here, so the decision and the act on it are in one
        // place: a page that could only be read would send someone back to the list to
        // press play on the row they had just left.
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(if (state.progressFraction > 0f) "Resume" else "Play")
                }
                DownloadButton(
                    download = state.download,
                    onDownload = onDownload,
                    onRemove = onRemoveDownload,
                )
                RowActionsMenu(
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    isFinished = state.isFinished,
                    onSetFinished = onSetFinished,
                )
            }
        }

        item {
            val notes = remember(episode.description) { parseShowNotes(episode.description) }
            if (notes.isEmpty) {
                Text(
                    // Said rather than left blank: a page that simply stops under the play
                    // button reads as one that failed to load its notes, and the reader
                    // then goes looking for them.
                    "This episode has no show notes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ShowNotesText(notes = notes, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
