package io.github.lightheaded.lugu.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The collections of the library in view.
 *
 * A fourth way into the library, alongside author, series and narrator — and the one most
 * likely to be what somebody is actually looking for, because it is the only grouping a
 * person made rather than one the metadata implies.
 *
 * The list draws from the mirror, so it is here on a cold start with no network. Only the
 * refresh behind it needs the server, and when that fails it says so in the status line
 * under the top bar rather than by emptying the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    onBack: () -> Unit,
    onOpenCollection: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CollectionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Collections") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // A button rather than a pull gesture, because the list is often only a
                    // few rows tall and there is nothing to pull. It stands in for the
                    // refresh the app has just declined to make on its own: opening this
                    // screen twice in a minute does not re-fetch several megabytes.
                    //
                    // It stays put while the refresh runs, greyed rather than replaced by a
                    // spinner. Swapping the two moved the button — they are not the same
                    // width — so the control someone had just pressed jumped under their
                    // finger. That a refresh is running is said under the bar instead.
                    IconButton(
                        onClick = { viewModel.refresh(force = true) },
                        enabled = !state.isSyncing,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            CollectionsList(
                state = state,
                onQueryChange = viewModel::setQuery,
                onOpenCollection = onOpenCollection,
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            // Overlaid rather than placed in the column, so that a refresh starting and a
            // failure appearing do not shift the list underneath. The error used to be a
            // line of red text between the search box and the first row, which pushed every
            // collection down the moment the server could not be reached.
            StatusStrip(
                status = state.error?.let { Status.Problem(it) }
                    ?: Status.Working("Refreshing collections").takeIf { state.isSyncing },
                onDismiss = viewModel::dismissError,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = padding.calculateTopPadding()),
            )
        }
    }
}

@Composable
private fun CollectionsList(
    state: CollectionsUiState,
    onQueryChange: (String) -> Unit,
    onOpenCollection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        // The box is offered only once the list is long enough to need it, which is the
        // opposite of the author page: there are hundreds of authors and rarely more
        // than a few collections.
        if (searchEarnsItsPlace(state.total)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text("Search collections", maxLines = 1) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (state.collections.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    emptyCollectionsLine(state.total, state.query),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(state.collections, key = { it.id }) { collection ->
                ListItem(
                    headlineContent = {
                        Text(collection.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = { Text(collectionCountLine(collection.itemCount)) },
                    modifier = Modifier.clickable { onOpenCollection(collection.id) },
                )
            }
        }
    }
}

/**
 * One collection, as a grid of the same cards the library grid uses.
 *
 * The cover is how people recognise a book they own, and a page that looked nothing like
 * the grid it was reached from would have to be learned separately — the same reasoning as
 * the author and series pages, and the same [ItemCard].
 *
 * There is no sort control here, deliberately. Every other list in the app offers one; this
 * one is in the order somebody put it in, and offering to reorder it would suggest the app
 * could save that order back, which it cannot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    onBack: () -> Unit,
    onOpenItem: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CollectionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(state.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state.rows.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (state.loaded) {
                        "Nothing in this collection. Add a book to it from the book's own page."
                    } else {
                        "Loading…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            items(state.rows, key = { it.item.id }) { row ->
                ItemCard(
                    row = row,
                    coverUrl = viewModel.coverUrl(row.item.id),
                    onClick = { onOpenItem(row.item.id) },
                )
            }
        }
    }
}
