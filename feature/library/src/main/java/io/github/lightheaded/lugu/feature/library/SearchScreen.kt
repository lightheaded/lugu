package io.github.lightheaded.lugu.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.lightheaded.lugu.core.db.BrowseGroup
import io.github.lightheaded.lugu.core.model.LibraryItem
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.sync.BrowseKind
import io.github.lightheaded.lugu.core.sync.EpisodeHit
import io.github.lightheaded.lugu.core.sync.SearchEverything
import java.util.Locale

/**
 * One search across everything: books, podcasts, episodes, authors, narrators, series and
 * collections, reached from the top bar of Home.
 *
 * The Library tab's own search box stays. It narrows one library's grid, under the sort
 * and the filter. This screen answers "where is that thing", wherever it is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenItem: (itemId: String) -> Unit,
    onOpenEpisode: (itemId: String, episodeId: String) -> Unit,
    onOpenGroup: (kind: String, name: String) -> Unit,
    onOpenCollection: (collectionId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }

    // The screen is opened to type into, so the keyboard comes up with it.
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        SearchContent(
            state = state,
            coverUrlFor = { viewModel.coverUrl(it) },
            onQueryChange = viewModel::setQuery,
            onOpenItem = onOpenItem,
            onOpenEpisode = onOpenEpisode,
            onOpenGroup = onOpenGroup,
            onOpenCollection = onOpenCollection,
            fieldModifier = Modifier.focusRequester(focus),
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

/**
 * Everything the search screen draws, from plain state, so that a screenshot test draws
 * the real screen and not a copy of it.
 *
 * Each kind of result is a section with its first [SECTION_PREVIEW] rows. "Show all" opens
 * the rest of that section only, so a search that finds forty episodes does not push the
 * one author it found off the screen.
 */
@Composable
internal fun SearchContent(
    state: SearchUiState,
    coverUrlFor: (itemId: String) -> String?,
    onQueryChange: (String) -> Unit,
    onOpenItem: (itemId: String) -> Unit,
    onOpenEpisode: (itemId: String, episodeId: String) -> Unit,
    onOpenGroup: (kind: String, name: String) -> Unit,
    onOpenCollection: (collectionId: String) -> Unit,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    // Opened sections close again when the words change, because they are new results.
    var opened by rememberSaveable(state.results.query) { mutableStateOf(emptyList<String>()) }
    val needle = SearchEverything.needleOf(state.results.query)

    Column(modifier) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = { Text("Books, episodes, authors, narrators…", maxLines = 1) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = fieldModifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )

        val hint = searchHint(state)
        if (hint != null) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        val results = state.results
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            fun <T> section(
                key: String,
                label: String,
                rows: List<T>,
                rowKey: (T) -> String,
                row: @Composable (T) -> Unit,
            ) {
                if (rows.isEmpty()) return
                val open = key in opened
                item(key = "header-$key") { SectionHeader(label, rows.size) }
                items(if (open) rows else rows.take(SECTION_PREVIEW), key = { "$key-${rowKey(it)}" }) { row(it) }
                if (!open && rows.size > SECTION_PREVIEW) {
                    item(key = "more-$key") {
                        TextButton(
                            onClick = { opened = opened + key },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) { Text("Show all ${rows.size}") }
                    }
                }
            }

            section("items", itemSectionLabel(results.items), results.items, { it.id }) { item ->
                ItemResultRow(item, itemSubline(item, needle), coverUrlFor(item.id)) { onOpenItem(item.id) }
            }
            section("episodes", "Episodes", results.episodes, { it.episodeId }) { hit ->
                ListItem(
                    headlineContent = { Text(hit.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(episodeHitSubline(hit), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    modifier = Modifier.clickable { onOpenEpisode(hit.itemId, hit.episodeId) },
                )
            }
            section("authors", BrowseKind.AUTHORS.label, results.authors, { it.name }) { group ->
                GroupResultRow(group) { onOpenGroup(BrowseKind.AUTHORS.id, group.name) }
            }
            section("narrators", BrowseKind.NARRATORS.label, results.narrators, { it.name }) { group ->
                GroupResultRow(group) { onOpenGroup(BrowseKind.NARRATORS.id, group.name) }
            }
            section("series", BrowseKind.SERIES.label, results.series, { it.name }) { group ->
                GroupResultRow(group) { onOpenGroup(BrowseKind.SERIES.id, group.name) }
            }
            section("collections", "Collections", state.collections, { it.id }) { collection ->
                ListItem(
                    headlineContent = { Text(collection.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(collectionCountLine(collection.itemCount)) },
                    modifier = Modifier.clickable { onOpenCollection(collection.id) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String, count: Int) {
    Text(
        "$label · ${"%,d".format(Locale.UK, count)}",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun ItemResultRow(item: LibraryItem, subline: String?, coverUrl: String?, onClick: () -> Unit) {
    ListItem(
        leadingContent = {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (coverUrl != null) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        },
        headlineContent = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = subline?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun GroupResultRow(group: BrowseGroup, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(group.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(groupCountLine(group.itemCount, MediaType.BOOK)) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** How many rows a section shows before "Show all". */
internal const val SECTION_PREVIEW = 4

/**
 * What the screen says in place of results, or null when it has results to show.
 *
 * While the answer to new words is on its way, the screen keeps what it shows and says
 * nothing, so a search does not flash "nothing matches" at every keystroke.
 */
internal fun searchHint(state: SearchUiState): String? = when {
    state.query.isBlank() ->
        "Search books, podcasts, episodes, authors, narrators, series and collections."
    state.nothingFound -> "Nothing matches “${state.query.trim()}”."
    else -> null
}

/** "Books", "Podcasts", or both when the search found both. */
internal fun itemSectionLabel(items: List<LibraryItem>): String {
    val podcasts = items.count { it.mediaType == MediaType.PODCAST }
    return when (podcasts) {
        0 -> "Books"
        items.size -> "Podcasts"
        else -> "Books and podcasts"
    }
}

/**
 * The author and the series, and the narrator when the narrator is what matched.
 *
 * Without the narrator, a search for a narrator's name lists books that seem to have
 * nothing to do with the words.
 */
internal fun itemSubline(item: LibraryItem, needle: String): String? {
    val narratorMatched = needle.isNotEmpty() &&
        SearchEverything.contains(item.narratorName, needle) &&
        !SearchEverything.contains(item.title, needle) &&
        !SearchEverything.contains(item.authorName, needle)
    // First when it is the match, because a long line is cut at its end, and the reason
    // the book is on the list must not be the part that is cut.
    return listOfNotNull(
        item.narratorName?.takeIf { narratorMatched }?.let { "Read by $it" },
        "Podcast".takeIf { item.mediaType == MediaType.PODCAST },
        item.authorName?.takeIf { it.isNotBlank() },
        item.seriesName?.takeIf { it.isNotBlank() },
    ).joinToString(" · ").ifEmpty { null }
}

/** The podcast, then when the episode came out. */
internal fun episodeHitSubline(hit: EpisodeHit, nowMs: Long = System.currentTimeMillis()): String =
    listOfNotNull(hit.podcastTitle, formatPublished(hit.publishedAtMs, nowMs)).joinToString(" · ")
