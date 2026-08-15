package io.github.lightheaded.lugu.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

/**
 * Everything in one library, as a grid.
 *
 * The Library tab of [HomeScreen], and only that: the computed shelves that used to sit
 * above this grid now live on the Home tab. This screen answers "show me everything",
 * which is a browsing job and wants a picker, a search box and an ordering — not a row
 * of suggestions in front of it.
 *
 * There is no scaffold here. The shell owns the bars, so that the tab bar and the mini
 * player do not blink out of existence when the tab changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenItem: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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

        ListControlsBar(
            query = state.query,
            onQueryChange = viewModel::onQueryChange,
            searchPlaceholder = "Search title, author, narrator, series",
            // Size is offered on the downloads screen, where it means bytes on the phone.
            // The server's own size field counts the ebook and anything flagged excluded,
            // so ordering the library by it would rank books by something nobody fetched.
            sortOptions = ItemSort.entries
                .filter { it != ItemSort.SIZE }
                .map { SortOption(it.id, it.label) },
            selectedSortId = state.sort.id,
            onSortSelected = { viewModel.setSort(ItemSort.fromId(it)) },
            filters = ListFilter.entries,
            selectedFilter = state.filter,
            onFilterSelected = viewModel::setFilter,
        )

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
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.items, key = { it.item.id }) { row ->
                    ItemCard(
                        row = row,
                        coverUrl = viewModel.coverUrl(row.item.id),
                        onClick = { onOpenItem(row.item.id) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ItemCard(
    row: LibraryRow,
    coverUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
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
                contentDescription = row.item.title,
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
        }
        Spacer(Modifier.height(6.dp))
        Text(
            row.item.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        row.item.authorName?.let {
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
 * The tap handler takes the whole row rather than an id because what a tap means depends
 * on how far into the item the listener already is, and the shelf is not the place to
 * decide that.
 */
@Composable
internal fun ShelfRowView(
    title: String,
    rows: List<LibraryRow>,
    coverUrlFor: (String) -> String?,
    onOpenRow: (LibraryRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) return
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
            items(rows, key = { it.item.id }) { row ->
                ItemCard(
                    row = row,
                    coverUrl = coverUrlFor(row.item.id),
                    onClick = { onOpenRow(row) },
                    modifier = Modifier.width(140.dp),
                )
            }
        }
    }
}
