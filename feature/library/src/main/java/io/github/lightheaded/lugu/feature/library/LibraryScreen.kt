package io.github.lightheaded.lugu.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.lightheaded.lugu.core.model.ItemSort
import io.github.lightheaded.lugu.core.model.ListFilter
import io.github.lightheaded.lugu.core.sync.BrowseKind
import io.github.lightheaded.lugu.core.ui.StatusStrip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Everything in one library, as a grid.
 *
 * The Library tab of [HomeScreen], and only that: the computed shelves that used to sit
 * above this grid now live on the Home tab. This screen answers "show me everything",
 * which is a browsing job and wants a picker, a search box and an ordering — not a row
 * of suggestions in front of it.
 *
 * There is no scaffold here. The shell owns the bars, so that the tab bar and the mini
 * player do not blink out of existence when the tab changes. That is also why the
 * selection bar is inside this screen rather than replacing the shell's top bar: the bar
 * up there belongs to both tabs, and a selection on this one has no business changing it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenItem: (String) -> Unit,
    onBrowse: (kind: String) -> Unit,
    onOpenCollections: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
    // Hoisted by the tab shell, so the grid keeps its place while the other tab is in
    // front. A caller that owns no such state gets one of its own and loses nothing.
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Getting out of selection mode is the commonest thing to want, and back is where
    // people reach for it first — before they find the cross on the bar.
    BackHandler(enabled = state.selectionActive) { viewModel.clearSelection() }

    // The rail indexes whatever the grid is ordered by, so the letters come from the field
    // that did the ordering rather than always from the title.
    val letterKeys = remember(state.items, state.sort) {
        state.items.map { it.fastScrollKey(state.sort) }
    }
    val letters = remember(letterKeys) { fastScrollLetters(letterKeys) }
    val showRail = fastScrollEarnsItsPlace(
        itemCount = state.items.size,
        letterCount = letters.size,
        orderedAlphabetically = state.sort.isAlphabetical,
    )
    val currentLetter by remember(letterKeys) {
        derivedStateOf { letterKeys.getOrNull(gridState.firstVisibleItemIndex)?.let(::initialOf) }
    }

    // A batch action changes nothing on screen, so it says what it did and then stops
    // saying it. A note that stays until the next one reads as a state the screen is in.
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(NOTE_MS)
            viewModel.dismissMessage()
        }
    }

    Column(modifier = modifier) {
        if (state.libraries.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.libraries, key = { it.id }) { library ->
                    FilterChip(
                        selected = library.id == state.selectedLibraryId,
                        onClick = { viewModel.selectLibrary(library.id) },
                        label = { Text(library.name) },
                    )
                }
            }
        }

        if (state.selectionActive) {
            LibrarySelectionBar(state = state, viewModel = viewModel)
        } else {
            BrowseLinks(onBrowse = onBrowse, onOpenCollections = onOpenCollections)

            ListControlsBar(
                query = state.query,
                onQueryChange = viewModel::onQueryChange,
                searchPlaceholder = "Search title, author, narrator, series",
                // Size is offered on the downloads screen, where it means bytes on the
                // phone. The server's own size field counts the ebook and anything flagged
                // excluded, so ordering the library by it would rank books by something
                // nobody fetched.
                sortOptions = ItemSort.entries
                    .filter { it != ItemSort.SIZE }
                    .map { SortOption(it.id, it.label) },
                selectedSortId = state.sort.id,
                onSortSelected = { viewModel.setSort(ItemSort.fromId(it)) },
                filters = ListFilter.entries,
                selectedFilter = state.filter,
                onFilterSelected = viewModel::setFilter,
            )
        }

        // What a sync is doing, what a batch action just did and why either failed used to
        // be three lines of text right here, between the controls and the grid. Each of
        // them appeared and disappeared on its own, and every one of those moments pushed
        // the whole grid down or pulled it up — a jump nobody asked for, in the middle of
        // reading. All three are now said in the status line under the top bar, which is
        // drawn over the content and so moves nothing. See [StatusStrip] and [HomeScreen].

        // The grid is fed by the local database, so this gesture re-mirrors from
        // the server rather than being what makes content appear.
        PullToRefreshBox(
            isRefreshing = state.isPulling,
            onRefresh = viewModel::pullToRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (state.items.isEmpty()) {
                    LibraryEmptyState(
                        content = libraryEmptyContent(
                            state = state,
                            onClearSearch = { viewModel.onQueryChange("") },
                            onClearFilter = { viewModel.setFilter(ListFilter.ALL) },
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 140.dp),
                        // The rail is given its own strip rather than floating over the
                        // covers: an index you have to read through a book jacket is not an
                        // index.
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            top = 16.dp,
                            end = if (showRail) 16.dp + FAST_SCROLL_RAIL_WIDTH else 16.dp,
                            bottom = 16.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.items, key = { it.item.id }) { row ->
                            ItemCard(
                                row = row,
                                coverUrl = viewModel.coverUrl(row.item.id),
                                onClick = {
                                    if (state.selectionActive) {
                                        viewModel.toggleSelection(row.item.id)
                                    } else {
                                        onOpenItem(row.item.id)
                                    }
                                },
                                isSelected = row.item.id in state.selectedIds,
                                onLongClick = { viewModel.toggleSelection(row.item.id) },
                            )
                        }
                    }
                }

                if (showRail) {
                    FastScrollRail(
                        letters = letters,
                        currentLetter = currentLetter,
                        onLetterSelected = { letter ->
                            val target = firstIndexOfLetter(letterKeys, letter)
                            if (target >= 0) scope.launch { gridState.scrollToItem(target) }
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }

                StatusStrip(
                    status = state.statusLine(),
                    onDismiss = viewModel::dismissStatus,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

/** What the empty state says, and what pressing its one button does about it. */
private data class LibraryEmptyContent(
    val line: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/**
 * Why the grid has nothing in it, told apart.
 *
 * A first sync, a search that matched nothing and a filter that excludes everything used
 * to draw the same blank space. Search and filter are checked first because they are what
 * the reader just did — a query or a filter chip is the more specific answer to "why is
 * this empty" than a sync that happens to be running at the same moment. Only once neither
 * explains it does a sync in progress get the word, with a truly empty library last: the
 * one cause that offers nothing to undo.
 */
private fun libraryEmptyContent(
    state: LibraryUiState,
    onClearSearch: () -> Unit,
    onClearFilter: () -> Unit,
): LibraryEmptyContent = when {
    state.query.isNotBlank() -> LibraryEmptyContent(
        line = "No matches for “${state.query}”.",
        actionLabel = "Clear search",
        onAction = onClearSearch,
    )
    state.filter != ListFilter.ALL -> LibraryEmptyContent(
        line = "No items match “${state.filter.label}”.",
        actionLabel = "Clear filter",
        onAction = onClearFilter,
    )
    state.isSyncing -> LibraryEmptyContent(line = "Syncing your library…")
    else -> LibraryEmptyContent(line = "Nothing in this library yet.")
}

/**
 * The quiet text that stands in for the grid while it has nothing to show.
 *
 * Lives where the grid lives, inside the same [Box] the grid and the [StatusStrip] share,
 * so it moves nothing above it and covers no fixed control — see the Compose overlay rule
 * in `CLAUDE.md`.
 */
@Composable
private fun LibraryEmptyState(content: LibraryEmptyContent, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                content.line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            val label = content.actionLabel
            val onAction = content.onAction
            if (label != null && onAction != null) {
                TextButton(onClick = onAction) { Text(label) }
            }
        }
    }
}

/**
 * The other four ways into a library.
 *
 * Deliberately understated. Title is how this library is browsed by default and the grid
 * behind these is the answer most of the time; author, series, narrator and collections
 * are the other questions people ask of a library, not a rival to the thing already on
 * screen. Above the search box rather than below it, because they leave for another page —
 * putting them between the filters and the grid would read as controls over the grid.
 *
 * Collections sits with the other three rather than above them even though it is the odd
 * one out: the first three group by what the metadata says, and a collection groups by what
 * a person decided. That difference matters to how the list was built and not at all to
 * somebody looking for a way in.
 */
@Composable
private fun BrowseLinks(
    onBrowse: (kind: String) -> Unit,
    onOpenCollections: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrowseKind.entries.forEach { kind ->
            TextButton(onClick = { onBrowse(kind.id) }) {
                Text(kind.label, style = MaterialTheme.typography.labelLarge)
            }
        }
        TextButton(onClick = onOpenCollections) {
            Text("Collections", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * What is worth doing to a handful of books at once.
 *
 * Both directions of the finished mark are offered rather than one toggle: a selection can
 * hold books in either state, so a single button would have to guess which way the whole
 * batch is meant to go, and would guess wrong half the time.
 */
@Composable
private fun LibrarySelectionBar(state: LibraryUiState, viewModel: LibraryViewModel) {
    val any = state.selectedIds.isNotEmpty()

    SelectionBar(
        selectedCount = state.selectedIds.size,
        onClear = viewModel::clearSelection,
        onSelectAll = viewModel::selectAllVisible,
        actions = listOf(
            SelectionAction("Download", Icons.Default.Download, viewModel::downloadSelected, any),
            SelectionAction(
                "Add to queue",
                Icons.AutoMirrored.Filled.PlaylistAdd,
                viewModel::addSelectedToQueue,
                any,
            ),
            SelectionAction(
                "Mark as finished",
                Icons.Default.TaskAlt,
                { viewModel.setSelectedFinished(true) },
                any,
            ),
            SelectionAction(
                "Mark as not finished",
                Icons.Default.RemoveDone,
                { viewModel.setSelectedFinished(false) },
                any,
            ),
        ),
    )
}

/**
 * The text the rail indexes this row by.
 *
 * It has to be the field the ordering used, or the letters run in an order the rows do not.
 * An unattributed book files under the same bucket as everything else without a letter,
 * which is where sorting by author has already put it: last.
 */
private fun LibraryRow.fastScrollKey(sort: ItemSort): String =
    if (sort == ItemSort.AUTHOR) item.authorName.orEmpty() else item.title

/** Long enough to read a short sentence, short enough not to become part of the screen. */
private const val NOTE_MS = 4_000L

/**
 * One cover, with what it is under it.
 *
 * [title] and [subtitle] default to the item's own, which is what a grid of items wants.
 * The continue shelf overrides them because what is being continued there may be one
 * episode of a podcast, and a card headed with the name of the show would be the same card
 * three times over for somebody with three episodes on the go.
 *
 * [playsOnTap] is the caller saying that a tap on this card starts audio, and the card
 * saying so back to the reader as a play badge on the cover. Two cards side by side on a
 * shelf used to do different things — one resumed, one opened a page — with nothing on
 * either to say which; audio with no warning is a surprise, and a page where a play was
 * expected is a broken promise. The badge is the warning, and the click label says the
 * same thing to TalkBack, which cannot see a badge.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ItemCard(
    row: LibraryRow,
    coverUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    title: String = row.item.title,
    subtitle: String? = row.item.authorName,
    onLongClick: (() -> Unit)? = null,
    playsOnTap: Boolean = false,
) {
    Column(
        modifier = modifier.combinedClickable(
            onClickLabel = if (playsOnTap) "play" else "open",
            onLongClick = onLongClick,
            onClick = onClick,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = coverUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (row.progressFraction > 0f) {
                // Three device-independent pixels of colour is the whole of what a sighted
                // reader gets, and TalkBack got nothing at all — a bar with no description
                // is skipped, so the one fact that distinguishes a part-heard book from an
                // untouched one was unavailable to anyone using a screen reader.
                LinearProgressIndicator(
                    progress = { row.progressFraction },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .semantics { contentDescription = row.progressDescription },
                )
            }
            if (playsOnTap) {
                // Above the progress strip rather than over it: both facts are drawn at
                // the bottom edge, and the badge covering the bar would hide the very
                // progress that makes this card resume. The icon is decorative to
                // TalkBack — the click label already says "play" where it matters.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 6.dp, bottom = 9.dp)
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (isSelected) {
                // A scrim over the whole cover and a tick in the middle of it. An outline
                // was the first instinct and the wrong one: these are 140dp of somebody
                // else's artwork, and a two-pixel border against a busy jacket is invisible
                // at arm's length — which on a grid means acting on the wrong eight books.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)),
                )
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.onPrimary, CircleShape),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One shelf.
 *
 * The tap handler takes the whole card rather than an id because what a tap means depends
 * on how far into the thing the listener already is, and the shelf is not the place to
 * decide that. Keyed on [ShelfCard.key] rather than on the item id: the continue shelf
 * lists episodes, so one podcast can be on it several times, and a duplicate key is a
 * crash in Compose rather than a card that merely looks wrong.
 *
 * [onMore] is where the rest of this shelf lives — the Library tab, filtered to the same
 * kind of thing. It is reachable from the header at one end and, when [hasMore] says the
 * row was cut short, from a "See all" tile at the other: the header is where somebody
 * decides to see more before scrolling, and the end of the row is where they find out
 * they want to.
 */
@Composable
internal fun ShelfRowView(
    title: String,
    cards: List<ShelfCard>,
    coverUrlFor: (String) -> String?,
    onOpenCard: (ShelfCard) -> Unit,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
    hasMore: Boolean = false,
) {
    if (cards.isEmpty()) return
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onMore != null) {
                        Modifier.clickable(onClickLabel = "show all in the Library tab", onClick = onMore)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (onMore != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(cards, key = { it.key }) { card ->
                ItemCard(
                    row = card.row,
                    coverUrl = coverUrlFor(card.itemId),
                    onClick = { onOpenCard(card) },
                    title = card.title,
                    subtitle = card.secondary,
                    playsOnTap = card.tapResumes,
                    modifier = Modifier.width(140.dp),
                )
            }
            if (hasMore && onMore != null) {
                item(key = "see-all") {
                    SeeAllCard(onClick = onMore, modifier = Modifier.width(140.dp))
                }
            }
        }
    }
}

/**
 * The tile after the last card, for the moment the row runs out and the appetite has not.
 *
 * Cover-sized and cover-shaped so the row ends with a full member of itself rather than
 * with a small link hanging in space — and so the reader's thumb, already in the rhythm
 * of the row, lands on it the same way it landed on the covers.
 */
@Composable
private fun SeeAllCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClickLabel = "show all in the Library tab", onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "See all",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
