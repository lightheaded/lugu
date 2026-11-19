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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.lightheaded.lugu.core.model.PodcastEpisode
import io.github.lightheaded.lugu.core.model.parseShowNotes
import kotlinx.coroutines.withTimeoutOrNull

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
    /** Opens the show's own page, from the show's name in the bar. */
    onOpenShow: (itemId: String) -> Unit = {},
    viewModel: EpisodeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissMessage()
    }

    val pendingUndo by viewModel.undo.collectAsStateWithLifecycle()
    val noticeMillis by viewModel.noticeMillis.collectAsStateWithLifecycle()

    // The change has already happened; this is the way back, for as long as the notice
    // setting says. A timeout keeps the change, as on the podcast page.
    LaunchedEffect(pendingUndo, noticeMillis) {
        val pending = pendingUndo ?: return@LaunchedEffect
        val result = withTimeoutOrNull(noticeMillis) {
            snackbarHostState.showSnackbar(
                message = pending.text,
                actionLabel = "Undo",
                withDismissAction = true,
                duration = SnackbarDuration.Indefinite,
            )
        }
        if (result == SnackbarResult.ActionPerformed) viewModel.undo(pending) else viewModel.dismissUndo(pending)
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            EpisodeTopBar(
                showTitle = state.showTitle,
                showItemId = state.showItemId,
                onBack = onBack,
                onOpenShow = onOpenShow,
            )
        },
    ) { padding ->
        val episode = state.episode
        if (episode == null) {
            // Nothing until the sync attempt ends: a show never opened on this phone has no
            // rows yet, and "gone" said before the sync would be false.
            if (!state.loaded) return@Scaffold
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        // A notification can outlive the episode it names — a feed can
                        // withdraw one, and a re-mirror then takes the row away underneath
                        // the tap. Saying so is better than an empty page.
                        "This episode is no longer in the feed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The show is still there, and is where the reader goes next.
                    state.showItemId?.let { showId ->
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = { onOpenShow(showId) }) {
                            Text("Open the show")
                        }
                    }
                }
            }
            return@Scaffold
        }

        EpisodeDetail(
            state = state,
            episode = episode,
            onPlay = {
                viewModel.onPlayStarted()
                onPlay(episode.libraryItemId, episode.id)
            },
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
 * The bar: back, and the show's name.
 *
 * The show, not the episode. The episode's own title is the first line of the page
 * underneath and is far too long for a bar; what a reader needs from the bar is which feed
 * this arrived in. The name is also the way to the show: a reader who came from a
 * notification has no show page under this one, and the name is where they look for it.
 * It looks the same as before, so the bar does not change, and TalkBack says what it does.
 *
 * [showItemId] comes from the route, so the link works before the episode loads and after
 * the feed has withdrawn it. With no id the name is plain text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodeTopBar(
    showTitle: String,
    showItemId: String?,
    onBack: () -> Unit,
    onOpenShow: (itemId: String) -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                showTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (showItemId != null) {
                    // 48 dp tall, so the name is as easy to hit as the back arrow.
                    Modifier
                        .clickable(
                            onClickLabel = "Open the show",
                            role = Role.Button,
                        ) { onOpenShow(showItemId) }
                        .minimumInteractiveComponentSize()
                } else {
                    Modifier
                },
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
    )
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
            Row {
                // Small, beside the title: it says which show at a glance, and the notes
                // stay as high on the page as before.
                state.coverUrl?.let { url ->
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(episode.title, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        subline,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.progressFraction > 0f) {
            item {
                // The bar alone: the time left is in the play button, so an "x of y" line
                // here would say the same thing twice.
                LinearProgressIndicator(
                    progress = { state.progressFraction },
                    modifier = Modifier.fillMaxWidth(),
                )
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
                    Text(
                        playLabel(state.isFinished, state.positionSec, episode.durationSec),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
