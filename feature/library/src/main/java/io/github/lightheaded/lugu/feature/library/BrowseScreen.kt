package io.github.lightheaded.lugu.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.model.Series
import io.github.lightheaded.lugu.core.sync.BrowseKind
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * The authors, series or narrators of the library, as a list of names to open.
 *
 * These pages exist because the item page has always shown an author and a narrator and
 * has never been able to link them anywhere; a link to a dead end is worse than plain
 * text, so the destination comes first and the links follow.
 *
 * The search box is not the shared [ListControlsBar]: that bar offers a sort and a filter
 * as well, and neither means anything to a list of names — there is nothing to order by
 * but the name, and nothing to filter on. What a list of several hundred authors does
 * need is the box, which is the same problem the episode list had.
 *
 * It also needs the same A–Z rail the grid has, and for a stronger reason: a grid row holds
 * three covers and a name list holds one name, so four hundred authors is four hundred rows
 * to flick past. It is the grid's [FastScrollRail], not a second one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onBack: () -> Unit,
    onOpenGroup: (kind: String, name: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(state.kind.label) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                label = { Text("Search ${state.kind.label.lowercase(Locale.UK)}", maxLines = 1) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (state.groups.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(
                        emptyBrowseLine(state.kind, state.totalGroups, state.query),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            // The rail indexes the groups the screen is actually drawing, which is the list
            // after the search box. Indexing the unfiltered set instead would offer a letter
            // the search has already removed, and send the finger to a row that is not there.
            val letterKeys = remember(state.groups) { state.groups.map { it.name } }
            val letters = remember(letterKeys) { fastScrollLetters(letterKeys) }
            val showRail = fastScrollEarnsItsPlace(
                itemCount = letterKeys.size,
                letterCount = letters.size,
                // A list of names has one possible ordering and is always in it, so the
                // rail's precondition is met by construction rather than by a setting.
                orderedAlphabetically = true,
            )
            val currentLetter by remember(letterKeys) {
                derivedStateOf { letterKeys.getOrNull(listState.firstVisibleItemIndex)?.let(::initialOf) }
            }

            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // The rail gets a strip of its own rather than floating over the names:
                    // a two-line author's name running under the letters is unreadable, and
                    // the row it belongs to is unhittable.
                    contentPadding = PaddingValues(
                        end = if (showRail) FAST_SCROLL_RAIL_WIDTH else 0.dp,
                        bottom = 32.dp,
                    ),
                ) {
                    items(state.groups, key = { it.name }) { group ->
                        ListItem(
                            headlineContent = {
                                Text(group.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = { Text(groupCountLine(group.itemCount, state.mediaType)) },
                            modifier = Modifier.clickable { onOpenGroup(state.kind.id, group.name) },
                        )
                    }
                }

                if (showRail) {
                    FastScrollRail(
                        letters = letters,
                        currentLetter = currentLetter,
                        onLetterSelected = { letter ->
                            val target = firstIndexOfLetter(letterKeys, letter)
                            if (target >= 0) scope.launch { listState.scrollToItem(target) }
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
    }
}

/**
 * One author, one series or one narrator, and everything of theirs in the library.
 *
 * A grid of the same cards the library grid uses, rather than a list of titles: the cover
 * is how people recognise a book they own, and a page that looked nothing like the grid it
 * was reached from would have to be learned separately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseGroupScreen(
    onBack: () -> Unit,
    onOpenItem: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BrowseGroupViewModel = hiltViewModel(),
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
                        "Nothing here any more. The library may have been re-synced since " +
                            "this page was opened."
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
                Column {
                    ItemCard(
                        row = row,
                        coverUrl = viewModel.coverUrl(row.item.id),
                        onClick = { onOpenItem(row.item.id) },
                    )
                    // Only a series page numbers its entries, and it is the whole reason
                    // that page exists. Under the card rather than over it, so that a
                    // volume the server gave no number to does not push its cover out of
                    // line with the rest of the row.
                    if (state.kind == BrowseKind.SERIES) {
                        val sequence = remember(row.item.seriesName, state.name) {
                            seriesSequenceLabelWithin(row.item.seriesName, state.name)
                        }
                        sequence?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * "12 books", or "12 podcasts" in a library of those.
 *
 * The count is what makes a wall of four hundred names usable: it separates a name worth
 * opening from a single misfiled item.
 */
internal fun groupCountLine(count: Int, mediaType: MediaType): String {
    val noun = when {
        mediaType == MediaType.PODCAST && count == 1 -> "podcast"
        mediaType == MediaType.PODCAST -> "podcasts"
        count == 1 -> "book"
        else -> "books"
    }
    return "%,d %s".format(Locale.UK, count, noun)
}

/**
 * "Book 2", or nothing at all.
 *
 * Roughly a third of series entries carry no number the server's string can be parsed
 * for, and inventing one would be worse than leaving the card unnumbered: a made-up
 * sequence is how someone gets handed volume three of a trilogy first.
 */
internal fun seriesSequenceLabel(sequence: Double?): String? {
    if (sequence == null) return null
    val number = if (sequence % 1.0 == 0.0) sequence.toInt().toString() else sequence.toString()
    return "Book $number"
}

/**
 * The number this book carries *in the series being looked at*.
 *
 * Anchored on the series name rather than read off the end of the string, because the
 * server joins every series a book is in into one field: a book in two of them renders as
 * "The Breakwater #1, The Tidelands #3", and taking the trailing number would label it
 * "Book 3" on The Breakwater's own page — the other series' number, on the one screen
 * whose entire job is putting a series in order.
 */
internal fun seriesSequenceLabelWithin(seriesName: String?, within: String): String? =
    seriesSequenceLabel(Series.sequenceWithin(seriesName, within))

/**
 * Why the list is empty, told apart.
 *
 * A library with no series in it and a search that matched none of them look identical on
 * screen, and the fix for each is the opposite of the fix for the other.
 */
internal fun emptyBrowseLine(kind: BrowseKind, totalGroups: Int, query: String): String = when {
    totalGroups == 0 -> "Nothing in this library has ${kind.singular.lowercase(Locale.UK)} " +
        "information yet."
    query.isBlank() -> "Nothing to show."
    else -> "No ${kind.label.lowercase(Locale.UK)} match that."
}
