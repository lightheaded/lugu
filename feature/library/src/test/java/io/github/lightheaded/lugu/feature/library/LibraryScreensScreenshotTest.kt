package io.github.lightheaded.lugu.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.lightheaded.lugu.core.download.DownloadStatus
import io.github.lightheaded.lugu.core.model.EpisodeSort
import io.github.lightheaded.lugu.core.model.ItemSort
import io.github.lightheaded.lugu.core.model.LibraryItem
import io.github.lightheaded.lugu.core.model.ListFilter
import io.github.lightheaded.lugu.core.model.MediaProgress
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.model.PodcastEpisode
import io.github.lightheaded.lugu.core.sync.BrowseKind
import io.github.lightheaded.lugu.core.sync.QueueItem
import io.github.lightheaded.lugu.core.sync.ShelfKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Catches a silent change to how the library, the item page and the queue look.
 *
 * Everything else in this module is asserted on in words — a sort order, a subline, which
 * rows a filter keeps. None of that notices a card that has lost its progress bar, a
 * selection tick that has stopped being visible over a dark cover, or a colour that only
 * goes wrong in one theme. That is what these are for, and it is why every screen is
 * recorded in both themes: a light-only baseline lets a dark-mode regression through, and
 * dark mode is when most of this app is used.
 *
 * On what is being photographed. Every screen in lugu takes a Hilt view model of a final
 * class whose dependencies are final classes over Room, DataStore and Ktor, so none of
 * them can be rendered from fabricated state as it stands. What is rendered here instead
 * is each screen's own components — [ItemCard], [ShelfRowView], [ListControlsBar],
 * [SelectionBar], [DownloadButton], [RowActionsMenu] — arranged the way the screen
 * arranges them. That covers the parts where the design decisions live and where a
 * regression would actually show; it does not cover the assembly, and a change to the
 * order of blocks in a screen file will not fail these.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class LibraryScreensScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the home tab shows what to pick up and the shelves under it`() {
        capture("home_tab_light", dark = false) { HomeShellPreview(libraryTab = false) }
    }

    @Test
    fun `the home tab reads correctly in the dark`() {
        capture("home_tab_dark", dark = true) { HomeShellPreview(libraryTab = false) }
    }

    @Test
    fun `the library tab shows the grid under its controls`() {
        capture("library_tab_light", dark = false) { HomeShellPreview(libraryTab = true) }
    }

    @Test
    fun `the library tab reads correctly in the dark`() {
        capture("library_tab_dark", dark = true) { HomeShellPreview(libraryTab = true) }
    }

    @Test
    fun `a selection is visible over every cover in the grid`() {
        capture("library_selection_light", dark = false) { LibrarySelectionPreview() }
    }

    @Test
    fun `a selection is visible over every cover in the dark`() {
        capture("library_selection_dark", dark = true) { LibrarySelectionPreview() }
    }

    @Test
    fun `a book page leads with resume, download and the group links`() {
        capture("item_book_light", dark = false) { BookPagePreview() }
    }

    @Test
    fun `a book page reads correctly in the dark`() {
        capture("item_book_dark", dark = true) { BookPagePreview() }
    }

    @Test
    fun `a podcast page leads with the episode list and its count`() {
        capture("item_podcast_light", dark = false) { PodcastPagePreview() }
    }

    @Test
    fun `a podcast page reads correctly in the dark`() {
        capture("item_podcast_dark", dark = true) { PodcastPagePreview() }
    }

    @Test
    fun `the queue numbers its rows and offers the drag handle`() {
        capture("queue_light", dark = false) { QueuePreview() }
    }

    @Test
    fun `the queue reads correctly in the dark`() {
        capture("queue_dark", dark = true) { QueuePreview() }
    }

    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { ScreenshotTheme(dark = dark, content = content) }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }
}

/**
 * lugu's palette without dynamic colour.
 *
 * The app prefers the wallpaper's colours from Android 12 onwards, which are by
 * definition not the same on two phones and so cannot be a baseline. These are the
 * colours lugu falls back to, which is what runs below Android 12 and wherever dynamic
 * colour is off — and they exercise the same light and dark code paths.
 */
@Composable
private fun ScreenshotTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) {
        darkColorScheme(primary = Color(0xFFEDC08B), secondary = Color(0xFFD6C3AE))
    } else {
        lightColorScheme(primary = Color(0xFF7A5A36), secondary = Color(0xFF6B5D4D))
    }
    MaterialTheme(colorScheme = colors) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

// ---------------------------------------------------------------------------------------
// Fabricated state. Invented titles and names throughout: nothing here comes from a real
// server, and nothing identifies one.
// ---------------------------------------------------------------------------------------

private fun book(
    id: String,
    title: String,
    author: String? = null,
    series: String? = null,
    narrator: String? = null,
    hours: Double = 9.0,
    description: String? = null,
) = LibraryItem(
    id = id,
    libraryId = "lib_books",
    mediaType = MediaType.BOOK,
    title = title,
    authorName = author,
    narratorName = narrator,
    seriesName = series,
    description = description,
    durationSec = hours * 3600,
)

private fun progress(fraction: Double, durationSec: Double) = MediaProgress(
    libraryItemId = "unused",
    currentTimeSec = durationSec * fraction,
    durationSec = durationSec,
    progress = fraction,
)

private val LIGHTHOUSE = book(
    id = "li_1",
    title = "The Lighthouse Wakes",
    author = "James T. R. Corven",
    narrator = "Jefferson Vale",
    series = "The Breakwater #1",
    hours = 11.5,
    description = "A keeper who has not spoken to anyone in four months finds the lamp " +
        "already lit, and has to decide which of the two of them is wrong.",
)

private val GRID_BOOKS = listOf(
    LibraryRow(LIGHTHOUSE, progress(0.42, 11.5 * 3600)),
    LibraryRow(book("li_2", "Salt and Shortwave", "Ada Merriweather", hours = 7.0), null),
    LibraryRow(book("li_3", "The Quiet Ordnance", "Peregrine Osk", hours = 14.0), progress(0.91, 14 * 3600.0)),
    LibraryRow(book("li_4", "Nine Days of Rain", "Ada Merriweather", hours = 5.5), null),
    LibraryRow(book("li_5", "A Map of Small Harbours", "Constance Yew", hours = 18.0), progress(0.07, 18 * 3600.0)),
    LibraryRow(book("li_6", "Winter Berth", "James T. R. Corven", hours = 9.5), null),
)

private val PODCAST = LibraryItem(
    id = "li_pod",
    libraryId = "lib_pods",
    mediaType = MediaType.PODCAST,
    title = "Coastal Signal",
    subtitle = "Long conversations about short waves",
    authorName = "Coastal Signal Collective",
    durationSec = 0.0,
    numEpisodes = 214,
)

/**
 * Published dates sit years in the past on purpose. The subline turns anything inside the
 * last week into "3 days ago", which would make the picture depend on the day it was
 * taken.
 */
private fun episode(id: String, title: String, season: String?, number: String, minutes: Int) =
    PodcastEpisode(
        id = id,
        libraryItemId = "li_pod",
        title = title,
        episodeNumber = number,
        season = season,
        publishedAtMs = 1_710_000_000_000L,
        durationSec = minutes * 60.0,
    )

private val EPISODES = listOf(
    EpisodeRow(
        episode("ep_1", "The 40-metre band, and who is still on it", "2", "14", 74),
        progress(0.33, 74 * 60.0),
        DownloadStatus("li_pod", "ep_1", "", null, "COMPLETED", 1f, 0, 0, null),
    ),
    EpisodeRow(
        episode("ep_2", "Ferry timetables as numbers stations", "2", "13", 58),
        null,
        DownloadStatus("li_pod", "ep_2", "", null, "DOWNLOADING", 0.35f, 0, 0, null),
    ),
    EpisodeRow(episode("ep_3", "A quiet hour on 500 kHz", "2", "12", 41), null, null),
)

private val QUEUE = listOf(
    QueueItem("li_2", null, "Salt and Shortwave", "Ada Merriweather", MediaType.BOOK, 7 * 3600.0, null, true, 0.0, false),
    QueueItem("li_pod", "ep_2", "Ferry timetables as numbers stations", "Coastal Signal", MediaType.PODCAST, 58 * 60.0, null, false, 0.0, false),
    QueueItem("li_6", null, "Winter Berth", "James T. R. Corven", MediaType.BOOK, 9.5 * 3600, null, false, 0.0, true),
)

// ---------------------------------------------------------------------------------------
// The screens, assembled from their own components.
// ---------------------------------------------------------------------------------------

/**
 * The signed-in shell: one top bar and one tab bar over whichever tab is showing.
 *
 * Both tabs are photographed because the split between them — "what should I play now"
 * against "show me everything" — is the decision the shell exists to carry, and a
 * regression that merged them back would be invisible to every other test here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeShellPreview(libraryTab: Boolean) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("lugu") },
                actions = {
                    IconButton(onClick = {}) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Up next")
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.Download, contentDescription = "Downloads")
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = !libraryTab,
                    onClick = {},
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Home") },
                )
                NavigationBarItem(
                    selected = libraryTab,
                    onClick = {},
                    icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null) },
                    label = { Text("Library") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (libraryTab) LibraryTabPreview() else HomeTabPreview()
        }
    }
}

@Composable
private fun HomeTabPreview() {
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item { ContinueCardPreview(GRID_BOOKS.first()) }
        item {
            ShelfRowView(
                title = ShelfKind.NEXT_IN_SERIES.label,
                rows = GRID_BOOKS.drop(1),
                coverUrlFor = { null },
                onOpenRow = {},
            )
        }
        item {
            ShelfRowView(
                title = ShelfKind.SHORT_LISTENS.label,
                rows = GRID_BOOKS.reversed(),
                coverUrlFor = { null },
                onOpenRow = {},
            )
        }
    }
}

/** The one-tap way back into whatever was last playing, as the Home tab renders it. */
@Composable
private fun ContinueCardPreview(row: LibraryRow) {
    val duration = row.progress?.durationSec ?: row.item.durationSec
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "Continue listening",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(row.item.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { row.progressFraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatDuration(duration * (1f - row.progressFraction))} left",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledIconButton(onClick = {}, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Resume ${row.item.title}")
            }
        }
    }
}

@Composable
private fun LibraryTabPreview() {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrowseKind.entries.forEach { kind ->
                TextButton(onClick = {}) {
                    Text(kind.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        ListControlsBar(
            query = "",
            onQueryChange = {},
            searchPlaceholder = "Search title, author, narrator, series",
            sortOptions = ItemSort.entries.filter { it != ItemSort.SIZE }.map { SortOption(it.id, it.label) },
            selectedSortId = ItemSort.TITLE.id,
            onSortSelected = {},
            filters = ListFilter.entries,
            selectedFilter = ListFilter.ALL,
            onFilterSelected = {},
        )
        ItemGridPreview(selected = emptySet())
    }
}

/**
 * The grid with a selection running.
 *
 * The scrim and the tick are the whole point of this one. The first attempt marked a
 * chosen card with a two-pixel outline, which disappears against a busy jacket at arm's
 * length — and on a grid that means acting on the wrong eight books.
 */
@Composable
private fun LibrarySelectionPreview() {
    Column {
        SelectionBar(
            selectedCount = 3,
            onClear = {},
            onSelectAll = {},
            actions = listOf(
                SelectionAction("Download", Icons.Default.Download, {}),
                SelectionAction("Add to queue", Icons.AutoMirrored.Filled.PlaylistAdd, {}),
                SelectionAction("Mark as finished", Icons.Default.TaskAlt, {}),
                SelectionAction("Mark as not finished", Icons.Default.RemoveDone, {}),
            ),
        )
        ItemGridPreview(selected = setOf("li_1", "li_3", "li_4"))
    }
}

@Composable
private fun ItemGridPreview(selected: Set<String>) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(GRID_BOOKS, key = { it.item.id }) { row ->
            ItemCard(
                row = row,
                coverUrl = null,
                onClick = {},
                isSelected = row.item.id in selected,
                onLongClick = {},
            )
        }
    }
}

/**
 * A book's page: resume, download and the queue menu on one row, then the group links.
 *
 * A book and a podcast are photographed separately because the page is genuinely two
 * pages — a book gets a play row and no list, a podcast gets a list and no play row — and
 * the two have regressed independently before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookPagePreview() {
    ItemPageScaffold(LIGHTHOUSE.title) {
        item { ItemHeaderPreview(LIGHTHOUSE) }
        item {
            Column {
                GroupLinkPreview(null, "James T. R. Corven")
                GroupLinkPreview("Read by", "Jefferson Vale")
                GroupLinkPreview("Book 1 of", "The Breakwater")
            }
        }
        item {
            Column {
                LinearProgressIndicator(progress = { 0.42f }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatDuration(0.42 * LIGHTHOUSE.durationSec)} of " +
                        formatDuration(LIGHTHOUSE.durationSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Resume")
                }
                DownloadButton(download = null, onDownload = {}, onRemove = {})
                RowActionsMenu(onPlayNext = {}, onAddToQueue = {}, isFinished = false, onSetFinished = {})
            }
        }
        item { Text(LIGHTHOUSE.description.orEmpty(), style = MaterialTheme.typography.bodyMedium) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PodcastPagePreview() {
    ItemPageScaffold(PODCAST.title) {
        item { ItemHeaderPreview(PODCAST) }
        item { GroupLinkPreview(null, PODCAST.authorName.orEmpty()) }
        item {
            Column {
                Text("Episodes", style = MaterialTheme.typography.titleMedium)
                ListControlsBar(
                    query = "",
                    onQueryChange = {},
                    searchPlaceholder = "Search episodes",
                    sortOptions = EpisodeSort.entries.map { SortOption(it.id, it.label) },
                    selectedSortId = EpisodeSort.NEWEST.id,
                    onSortSelected = {},
                    filters = ListFilter.entries,
                    selectedFilter = ListFilter.ALL,
                    onFilterSelected = {},
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    episodeCountLine(EPISODES.size, PODCAST.numEpisodes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        items(EPISODES.size) { index -> EpisodeRowPreview(EPISODES[index]) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemPageScaffold(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun ItemHeaderPreview(item: LibraryItem) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column {
            Text(item.title, style = MaterialTheme.typography.titleLarge)
            item.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                formatDuration(item.durationSec),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GroupLinkPreview(prefix: String?, name: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        prefix?.let {
            Text(
                "$it ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun EpisodeRowPreview(row: EpisodeRow) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                row.episode.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                episodeSubline(row.episode),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.progressFraction > 0f) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { row.progressFraction },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            }
        }
        DownloadButton(download = row.download, onDownload = {}, onRemove = {}, compact = true)
        RowActionsMenu(
            onPlayNext = {},
            onAddToQueue = {},
            compact = true,
            isFinished = row.isFinished,
            onSetFinished = {},
        )
    }
}

/**
 * The one list the listener composes themselves.
 *
 * Photographed because the ordinal, the drag handle and the "suggested" marker are the
 * three things that tell a queued entry apart from one a continuation rule put there, and
 * losing any of them turns the queue back into a list that appears to reorder itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueuePreview() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Up next") },
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.Checklist, contentDescription = "Select entries")
                    }
                    TextButton(onClick = {}) { Text("Clear") }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(QUEUE.size) { index -> QueueRowPreview(QUEUE[index], index + 1) }
        }
    }
}

@Composable
private fun QueueRowPreview(item: QueueItem, position: Int) {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "$position",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        buildString {
                            item.author?.let { append(it).append(" · ") }
                            append(formatDuration(item.durationSec))
                            if (item.isSuggestion) append(" · suggested")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (item.isDownloaded) {
                        Icon(
                            Icons.Default.DownloadDone,
                            contentDescription = "Downloaded",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            IconButton(onClick = {}) {
                Icon(Icons.Default.Close, contentDescription = "Remove from queue")
            }
            Icon(
                Icons.Default.DragHandle,
                contentDescription = "Long-press and drag to reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}
