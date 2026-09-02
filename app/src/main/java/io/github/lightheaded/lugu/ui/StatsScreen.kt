package io.github.lightheaded.lugu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.intl.Locale as ComposeLocale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.model.DayTotal
import io.github.lightheaded.lugu.core.model.ListeningStats
import io.github.lightheaded.lugu.core.model.TitleTotal
import io.github.lightheaded.lugu.core.model.formatLength
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.StatsRepository
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * What the screen has to show, and whether it has anything yet.
 *
 * [stats] is null until the ledger has been read once. That is a different state from a
 * ledger with nothing in it, and the screen says something different for each: "reading"
 * is a moment, and "nothing recorded yet" is an answer.
 */
data class StatsUiState(
    val stats: ListeningStats? = null,
) {
    val isReading: Boolean get() = stats == null
}

@HiltViewModel
class StatsViewModel @Inject constructor(
    authRepository: AuthRepository,
    statsRepository: StatsRepository,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<StatsUiState> = authRepository.observeAccount()
        .flatMapLatest { account ->
            if (account == null) flowOf(null) else statsRepository.observe(account)
        }
        .map { StatsUiState(stats = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())
}

/**
 * How much has been listened to, and when.
 *
 * The ledger behind this has recorded every session since 15 August and nothing has ever
 * read it. The numbers are the local record and not the server's: they count what this
 * phone heard, including everything heard with no connection, which is the half a server
 * page cannot show.
 *
 * The body is [StatsContent] and takes its state as an argument, so the screenshot test
 * photographs the real layout rather than a copy of it. `PlaybackRecordScreenshotTest`
 * carries that copy because its screen reaches a Hilt view model directly, and the copy
 * is a second thing to keep in step. One screen doing that is enough.
 */
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StatsContent(state = state, onBack = onBack, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsContent(
    state: StatsUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Listening") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val stats = state.stats
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                stats == null -> item { Text("Reading the record…") }

                !stats.hasAnything -> item { NothingYetCard() }

                else -> {
                    item { TotalsCard(stats) }
                    item { DayChart(stats.recentDays, stats.busiestDaySeconds) }
                    item { StreakCard(stats) }
                    if (stats.bookSeconds > 0.0 && stats.podcastSeconds > 0.0) {
                        item { SplitCard(stats) }
                    }
                    if (stats.topTitles.isNotEmpty()) {
                        item {
                            Text(
                                text = "Most listened",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        stats.topTitles.forEach { title ->
                            item(key = "title-${title.libraryItemId}") { TitleRow(title) }
                        }
                    }
                    item { LocalRecordNote() }
                }
            }
        }
    }
}

/**
 * Said rather than left blank, because an empty screen reads as a broken screen.
 *
 * It also names *why* it is empty for the one case that surprises people: the ledger
 * starts when lugu starts, so a library with years of history on the server shows nothing
 * here on the first day.
 */
@Composable
private fun NothingYetCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Nothing recorded yet", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "These numbers count what this phone played. They start from the " +
                    "day lugu was installed, so listening you did elsewhere is not here.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TotalsCard(stats: ListeningStats) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = formatLength(stats.totalSeconds),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "in ${plural(stats.sessionCount, "session", "sessions")} " +
                    "across ${plural(stats.daysListened, "day", "days")}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Two labelled lines rather than one joined by a separator. The joined form
            // wrapped after "in the last", which left "30" alone on a line and reading as
            // a number in its own right.
            Text(
                text = "Last 7 days: ${formatLength(stats.last7Seconds)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = "Last 30 days: ${formatLength(stats.last30Seconds)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Fourteen days as bars.
 *
 * Bars sized by `Box` height rather than drawn on a `Canvas`, so the chart is made of real
 * composables. That matters twice: a screenshot test can photograph it without a graphics
 * backend, and each bar can carry its own spoken description. A canvas is one opaque
 * rectangle to a screen reader.
 *
 * Every bar keeps a visible stub at zero, so a quiet day reads as a day with nothing in it
 * rather than as a day the chart failed to place. See [MIN_BAR] — the first attempt got the
 * height right and the colour wrong, and drew a stub nobody could see.
 */
@Composable
private fun DayChart(days: List<DayTotal>, busiestSeconds: Double) {
    // Read from the composition rather than from `Locale.getDefault()`. The static call is
    // not observable, so a phone whose language changes under the app keeps the weekday
    // names it started with — and lint refuses it outright.
    val locale = Locale.forLanguageTag(ComposeLocale.current.toLanguageTag())
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("The last ${days.size} days", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                days.forEach { day ->
                    val date = LocalDate.ofEpochDay(day.day)
                    val fraction = if (busiestSeconds > 0.0) {
                        day.secondsListened / busiestSeconds
                    } else {
                        0.0
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clearAndSetSemantics {
                                contentDescription = spokenDay(date, day.secondsListened, locale)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(BAR_WIDTH)
                                .height(MIN_BAR + (MAX_BAR - MIN_BAR) * fraction.toFloat())
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (day.secondsListened > 0.0) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        // Not surfaceVariant: that is the card's own colour,
                                        // so a quiet day was drawn and still invisible. The
                                        // first attempt raised the stub's height, which was
                                        // never what hid it.
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                ),
                        )
                        Text(
                            text = date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StreakCard(stats: ListeningStats) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (stats.currentStreakDays > 0) {
                    "${plural(stats.currentStreakDays, "day", "days")} in a row"
                } else {
                    "No run going"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Longest run ${plural(stats.longestStreakDays, "day", "days")}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SplitCard(stats: ListeningStats) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Books and podcasts", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "${formatLength(stats.bookSeconds)} of books · " +
                    "${formatLength(stats.podcastSeconds)} of podcasts",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TitleRow(title: TitleTotal) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // A podcast carries the show's own name as its author, so the two lines read
            // "The Tidelands" twice. Found by looking at the recorded picture.
            if (title.author.isNotBlank() && title.author != title.title) {
                Text(
                    text = title.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = formatLength(title.secondsListened),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** The same promise the playback record makes, because this screen holds the same kind of record. */
@Composable
private fun LocalRecordNote() {
    Text(
        text = "This count stays on this phone. It is built from lugu's own record of " +
            "what played, not from the server.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * What a bar says out loud.
 *
 * The weekday initial under a bar is the visual label, and on its own it is useless to a
 * screen reader — fourteen bars would read as "M T W T F S S M T W T F S S". The full date
 * and the length replace it.
 */
private fun spokenDay(date: LocalDate, seconds: Double, locale: Locale): String {
    val day = date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
    val month = date.month.getDisplayName(TextStyle.FULL, locale)
    val whenIt = "$day ${date.dayOfMonth} $month"
    return if (seconds > 0.0) "$whenIt, ${formatLength(seconds)}" else "$whenIt, nothing"
}

private fun plural(count: Int, one: String, many: String): String =
    if (count == 1) "$count $one" else "$count $many"

private val BAR_WIDTH = 12.dp

/**
 * The height of a day with nothing in it.
 *
 * Four points and not two, so the stub reads as a deliberate mark. Height alone was not
 * enough: the stub was first drawn in `surfaceVariant`, which is the card's own colour, and
 * a quiet day has to be distinguishable from a day the chart failed to place.
 */
private val MIN_BAR = 4.dp
private val MAX_BAR = 72.dp
