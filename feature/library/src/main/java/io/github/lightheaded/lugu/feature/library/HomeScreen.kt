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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
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
import androidx.compose.material3.TextButton
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.lightheaded.lugu.core.model.ListFilter
import io.github.lightheaded.lugu.core.model.formatLengthCompact
import io.github.lightheaded.lugu.core.sync.ShelfKind
import io.github.lightheaded.lugu.core.sync.StartTab
import io.github.lightheaded.lugu.core.ui.Status
import io.github.lightheaded.lugu.core.ui.StatusStrip

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
    onBrowse: (kind: String) -> Unit,
    onOpenCollections: () -> Unit,
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
    val startTab by viewModel.startTab.collectAsStateWithLifecycle()
    val playingNow by viewModel.playingNow.collectAsStateWithLifecycle()

    // The stored preference only ever answers "which tab does lugu open on", so it is
    // consulted exactly once — while nobody has chosen a tab in this instance. Seeding the
    // saveable with it instead would let it win again after a rotation, and being sent back
    // to the start tab halfway through browsing is worse than never honouring it at all.
    var chosen by rememberSaveable { mutableStateOf<HomeTab?>(null) }
    val tab = chosen ?: startTab?.toHomeTab()

    // Hoisted above the `when` below, because the tab that is not in front leaves
    // composition and takes any state it owns with it. Held here, each tab keeps the place
    // it was left at — a glance at the other tab no longer costs a position deep in a long
    // grid. Both remember across a rotation, which is what rememberLazy*State already does.
    val homeListState = rememberLazyListState()
    val libraryGridState = rememberLazyGridState()

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
                    // Nothing conditional belongs in here. A top bar's actions are a
                    // right-aligned row, so a spinner that came and went at the end of it
                    // slid these three buttons sideways and back every time a sync started
                    // and finished — including the sync that runs by itself on launch, so
                    // the app twitched before anyone had touched it. What it was trying to
                    // say is now said under the bar, where saying it moves nothing.
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
                            onClick = { chosen = entry },
                            icon = { Icon(entry.icon, contentDescription = null) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        when (tab) {
            null -> Unit

            HomeTab.HOME -> Box(Modifier.fillMaxSize()) {
                HomeTabContent(
                    state = state,
                    playingNow = playingNow,
                    coverUrlFor = { viewModel.coverUrl(it) },
                    onOpenItem = onOpenItem,
                    onPlay = onPlay,
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onOpenShelf = { kind ->
                        libraryViewModel.setFilter(kind.libraryFilter())
                        chosen = HomeTab.LIBRARY
                    },
                    listState = homeListState,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
                StatusStrip(
                    status = libraryState.statusLine(),
                    onDismiss = libraryViewModel::dismissStatus,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = padding.calculateTopPadding()),
                )
            }

            HomeTab.LIBRARY -> LibraryScreen(
                onOpenItem = onOpenItem,
                onBrowse = onBrowse,
                onOpenCollections = onOpenCollections,
                modifier = Modifier.fillMaxSize().padding(padding),
                viewModel = libraryViewModel,
                gridState = libraryGridState,
            )
        }
    }
}

/**
 * The one thing worth saying right now, of everything the library screen tracks.
 *
 * Ordered by what a person needs first. A failure outranks a confirmation, because a batch
 * action that succeeded and a sync that then failed is a screen where the failure is the
 * news. A confirmation outranks work in progress, because it is the reply to something just
 * pressed and the sync will still be running a moment later to say so.
 */
internal fun LibraryUiState.statusLine(): Status? = when {
    error != null -> Status.Problem(error)
    message != null -> Status.Done(message)
    isSyncing -> Status.Working(syncNote?.text ?: "Syncing", syncNote?.fraction)
    else -> null
}

/** The stored preference names one of the two tabs; this is that name, as a tab. */
private fun StartTab.toHomeTab(): HomeTab = when (this) {
    StartTab.HOME -> HomeTab.HOME
    StartTab.LIBRARY -> HomeTab.LIBRARY
}

/**
 * The Library filter nearest to what a shelf is a preview of.
 *
 * The grid cannot ask every question a shelf asks — it has no filter for "next in a
 * series you started" or "short enough for a sitting" — so each shelf maps to the
 * filter that *contains* it: a superset, never a different answer. The jump means
 * "show me more of this", and more of it is exactly what a superset shows. Note also
 * that shelves set to span every library land in a grid scoped to the selected one,
 * which is the grid's standing rule rather than something the jump changes.
 */
private fun ShelfKind.libraryFilter(): ListFilter = when (this) {
    ShelfKind.CONTINUE, ShelfKind.ALMOST_FINISHED, ShelfKind.PICK_IT_BACK_UP -> ListFilter.IN_PROGRESS
    ShelfKind.DOWNLOADED -> ListFilter.DOWNLOADED
    ShelfKind.NEXT_IN_SERIES, ShelfKind.SHORT_LISTENS -> ListFilter.UNPLAYED
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
    playingNow: PlayingNow?,
    coverUrlFor: (String) -> String?,
    onOpenItem: (String) -> Unit,
    onPlay: (itemId: String, episodeId: String?) -> Unit,
    onTogglePlayPause: () -> Unit,
    onOpenShelf: (ShelfKind) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    // A tap on a shelf card means "carry on" for anything already started, and "tell me
    // about this" for anything not. It is a judgement call: opening the page for a book
    // someone is halfway through adds a step to the one action they almost certainly
    // wanted, while playing something unheard on a single tap is a surprise nobody asked
    // for. The card states the rule itself — see [ShelfCard.tapResumes], which is also
    // what draws the play badge, so the badge and the tap cannot drift apart. The episode
    // id comes from the card itself, so a podcast episode on the continue shelf plays
    // that episode rather than the top of the feed.
    val onOpenCard: (ShelfCard) -> Unit = { card ->
        if (card.tapResumes) onPlay(card.itemId, card.episodeId) else onOpenItem(card.itemId)
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        state.continueCard?.let { card ->
            item {
                // Whether this card is the thing in the player is a question about the
                // pair, not about the item: several episodes of one show can be on this
                // shelf at once, and matching on the item alone would have all of them
                // claiming to be playing together.
                val loaded = playingNow?.takeIf { it.isLoaded(card) }
                ContinueCard(
                    card = card,
                    coverUrl = coverUrlFor(card.itemId),
                    isPlaying = loaded?.isPlaying == true,
                    onResume = { onPlay(card.itemId, card.episodeId) },
                    // A card that is already loaded gets the transport; anything else gets
                    // a start. Sending a start to what is already playing would resolve the
                    // session again and buffer from the beginning of the resume.
                    onPlayPause = {
                        if (loaded != null) onTogglePlayPause() else onPlay(card.itemId, card.episodeId)
                    },
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
                cards = shelf.cards,
                coverUrlFor = coverUrlFor,
                onOpenCard = onOpenCard,
                onMore = { onOpenShelf(shelf.kind) },
                hasMore = shelf.hasMore,
            )
        }

        if (state.continueCard == null && state.shelves.isEmpty()) {
            item {
                Text(
                    // Two different emptinesses, and pointing at the Library tab is only
                    // useful for one of them. A minute after signing in that tab is empty
                    // too, and sending somebody to it makes the app look broken at the one
                    // moment they have nothing else to judge it by.
                    if (state.libraryHasArrived) {
                        "Nothing to pick up yet. The Library tab has everything on the server."
                    } else {
                        "Getting your library from the server. This happens once — " +
                            "what you have listened to will show up here."
                    },
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
 * Deliberately larger than a shelf card and above the shelves: it is a single thing rather
 * than a list because the answer to "where was I" is singular, and anything that makes
 * the reader choose between candidates has already lost the argument. For a podcast that
 * thing is an episode, so the episode is the title and the show is the line under it.
 *
 * [isPlaying] means "this card is what the player has loaded, and it is running" — the
 * caller decides that, because deciding it needs the episode id as well as the item id.
 * The button is the only playing-aware part: the card as a whole still means "carry on
 * with this", which is the same request whether or not it is already under way.
 */
@Composable
internal fun ContinueCard(
    card: ShelfCard,
    coverUrl: String?,
    isPlaying: Boolean,
    onResume: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    card.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                card.secondary?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { card.progressFraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // Nothing reaches this card unless it is part-heard, so the dash is for
                    // the one case left: a mirror with no duration to subtract from. "0s
                    // left" there would read as a book about to finish.
                    card.remainingSec.takeIf { it > 0 }
                        ?.let { "${formatLengthCompact(it)} left" } ?: "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(56.dp)) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription =
                        if (isPlaying) "Pause ${card.title}" else "Resume ${card.title}",
                )
            }
        }
    }
}
