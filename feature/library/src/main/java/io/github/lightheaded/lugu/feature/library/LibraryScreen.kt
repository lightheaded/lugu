package io.github.lightheaded.lugu.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.lightheaded.lugu.core.model.ItemSort
import io.github.lightheaded.lugu.core.model.ListFilter
import io.github.lightheaded.lugu.core.sync.BrowseKind
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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
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
    val showRail = fastScrollEarnsItsPlace(state.items.size, state.sort, letters.size)
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

        state.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        state.syncMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        state.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        // The grid is fed by the local database, so this gesture re-mirrors from
        // the server rather than being what makes content appear.
        PullToRefreshBox(
            isRefreshing = state.isSyncing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    // The rail is given its own strip rather than floating over the covers:
                    // an index you have to read through a book jacket is not an index.
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
) {
    Column(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
                LinearProgressIndicator(
                    progress = { row.progressFraction },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                )
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
 */
@Composable
internal fun ShelfRowView(
    title: String,
    cards: List<ShelfCard>,
    coverUrlFor: (String) -> String?,
    onOpenCard: (ShelfCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cards.isEmpty()) return
    Column(modifier = modifier) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
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
                    modifier = Modifier.width(140.dp),
                )
            }
        }
    }
}
