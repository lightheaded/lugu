package io.github.lightheaded.lugu.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage

/** The two jobs the signed-in app does, in the order they are wanted. */
private enum class HomeTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    LIBRARY("Library", Icons.AutoMirrored.Filled.LibraryBooks),
}

/**
 * The signed-in shell.
 *
 * Home and Library are two questions — "what should I play now" and "show me
 * everything" — that were being answered by one screen, with the shelves for the first
 * stacked on top of the grid for the second. They are separate tabs now.
 *
 * The tab lives in local state rather than in the back stack. Switching tabs is not
 * navigation anyone wants to press Back through, and putting it there would mean Back
 * out of the Library tab lands on Home instead of leaving the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenItem: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenQueue: () -> Unit,
    onPlay: (itemId: String, episodeId: String?) -> Unit,
    bottomContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    // The Library tab's view model is resolved here as well as inside LibraryScreen, and is
    // the same instance: both resolve against the destination that hosts them. The shell
    // needs it only to show that a sync is running, which is a property of the whole screen
    // rather than of one tab. The selected library is *not* passed this way — it lives in
    // LibraryPrefs, so both tabs read one value instead of one telling the other.
    val libraryViewModel: LibraryViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val libraryState by libraryViewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(HomeTab.HOME) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("lugu") },
                actions = {
                    IconButton(onClick = onOpenQueue) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Up next")
                    }
                    IconButton(onClick = onOpenDownloads) {
                        Icon(Icons.Default.Download, contentDescription = "Downloads")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    if (libraryState.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                },
            )
        },
        bottomBar = {
            Column {
                // Whatever is playing outlives the tab it was started from, so the mini
                // player is part of the shell rather than of either tab. It sits above the
                // tab bar, where every other media app puts it — the tabs are the floor of
                // the screen, and a control that moves between them reads as belonging to
                // neither.
                bottomContent()
                NavigationBar {
                    HomeTab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = { Icon(entry.icon, contentDescription = null) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        when (tab) {
            HomeTab.HOME -> HomeTabContent(
                state = state,
                coverUrlFor = { viewModel.coverUrl(it) },
                onOpenItem = onOpenItem,
                onPlay = onPlay,
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            HomeTab.LIBRARY -> LibraryScreen(
                onOpenItem = onOpenItem,
                modifier = Modifier.fillMaxSize().padding(padding),
                viewModel = libraryViewModel,
            )
        }
    }
}

/**
 * The shelves, and the one thing most likely to be wanted.
 *
 * Resuming is the commonest thing anyone does here and used to cost four steps — open,
 * find, scroll, tap, play — so the most recently played item gets the top of the screen
 * and a single tap.
 */
@Composable
private fun HomeTabContent(
    state: HomeUiState,
    coverUrlFor: (String) -> String?,
    onOpenItem: (String) -> Unit,
    onPlay: (itemId: String, episodeId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A tap on a shelf card means "carry on" for anything already started, and "tell me
    // about this" for anything not. It is a judgement call: opening the page for a book
    // someone is halfway through adds a step to the one action they almost certainly
    // wanted, while playing something unheard on a single tap is a surprise nobody asked
    // for. The episode id comes from the progress row, so a podcast resumes the episode
    // it was left on rather than starting the feed again.
    val onOpenRow: (LibraryRow) -> Unit = { row ->
        if (row.progress != null) onPlay(row.item.id, row.progress.episodeId) else onOpenItem(row.item.id)
    }

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
        state.continueRow?.let { row ->
            item {
                ContinueCard(
                    row = row,
                    coverUrl = coverUrlFor(row.item.id),
                    onResume = { onPlay(row.item.id, row.progress?.episodeId) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        if (state.shelvesSpanEverything) {
            // Said out loud rather than left to be inferred. Shelves that quietly ignored
            // the library picker were the original complaint, and a silent answer either
            // way is the part that made it a bug rather than a preference.
            item {
                Text(
                    "Shelves are showing every library.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        items(state.shelves, key = { it.kind.name }) { shelf ->
            ShelfRowView(
                title = shelf.kind.label,
                rows = shelf.rows,
                coverUrlFor = coverUrlFor,
                onOpenRow = onOpenRow,
            )
        }

        if (state.continueRow == null && state.shelves.isEmpty()) {
            item {
                Text(
                    "Nothing to pick up yet. The Library tab has everything on the server.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/**
 * The one-tap way back into whatever was last playing.
 *
 * Deliberately larger than a shelf card and above the shelves: it is a single item rather
 * than a list because the answer to "where was I" is singular, and anything that makes
 * the reader choose between candidates has already lost the argument.
 */
@Composable
private fun ContinueCard(
    row: LibraryRow,
    coverUrl: String?,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The progress row knows the duration of what was actually being played, which for a
    // podcast is the episode rather than the whole feed.
    val duration = row.progress?.durationSec?.takeIf { it > 0.0 } ?: row.item.durationSec
    val remaining = duration * (1f - row.progressFraction)

    Card(modifier = modifier.fillMaxWidth().clickable(onClick = onResume)) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Continue listening",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    row.item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { row.progressFraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatDuration(remaining)} left",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledIconButton(onClick = onResume, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Resume ${row.item.title}")
            }
        }
    }
}
