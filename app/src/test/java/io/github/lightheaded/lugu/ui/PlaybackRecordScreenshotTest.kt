package io.github.lightheaded.lugu.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.lightheaded.lugu.core.sync.DiaryEntry
import io.github.lightheaded.lugu.core.sync.PlaybackDiary
import io.github.lightheaded.lugu.core.sync.PlaybackEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Catches the "why playback stopped" record losing the thing that makes it worth having.
 *
 * The screen exists because "it stops sometimes and I cannot tell whether it crashed or
 * just stopped" is unanswerable from a log by anyone who is not willing to read a log. Two
 * pieces of it carry that weight and neither is asserted on anywhere else: the interpreted
 * sentence at the top, and the note under a line that means more than it says. A change
 * that demoted either into ordinary body text would leave every other test green.
 *
 * The counting and the grouping come from the real [PlaybackRecord]; the layout is the
 * screen's, reproduced here because [PlaybackRecordScreen] takes a Hilt view model over
 * the on-disk diary.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = RobolectricDeviceQualifiers.Pixel5,
    // A plain Application rather than lugu's own. Starting the real one builds the whole
    // Hilt graph, which reaches the encrypted token store, which asks for an AndroidKeyStore
    // algorithm Robolectric does not have. None of that is what is being photographed.
    application = Application::class,
)
class PlaybackRecordScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var originalZone: TimeZone

    /**
     * Every label on this screen is a clock reading, so the picture depends on the time
     * zone the machine taking it happens to be in unless it is pinned.
     */
    @Before
    fun pinTimeZone() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `the record leads with what the stops add up to`() {
        capture("playback_record_light", dark = false) { PlaybackRecordPreview(empty = false) }
    }

    @Test
    fun `the record reads correctly in the dark`() {
        capture("playback_record_dark", dark = true) { PlaybackRecordPreview(empty = false) }
    }

    @Test
    fun `an empty record still answers the question in its title`() {
        capture("playback_record_empty_light", dark = false) { PlaybackRecordPreview(empty = true) }
    }

    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { ScreenshotTheme(dark = dark, content = content) }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }
}

/**
 * lugu's palette without dynamic colour.
 *
 * The app prefers the wallpaper's colours from Android 12 onwards, which are by definition
 * not the same on two phones and so cannot be a baseline. These are the colours lugu falls
 * back to, which is what runs below Android 12 and wherever dynamic colour is off.
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

/** A Wednesday in March, 09:00 London. Fixed so that "Today" and the times never move. */
private const val NOW_MS = 1_710_493_200_000L

private fun at(minutesAgo: Long): Long = NOW_MS - minutesAgo * 60_000L

/**
 * A drive that went wrong in three of the ways the diary can tell apart: the audio taken
 * by something else, an error, and — the one the screen exists for — playback still
 * running when the process last stopped.
 */
private val ENTRIES = listOf(
    DiaryEntry(at(310), PlaybackEvent.PLAY_REQUESTED, "The Lighthouse Wakes"),
    DiaryEntry(at(309), PlaybackEvent.PLAYING),
    DiaryEntry(at(240), PlaybackEvent.SUPPRESSED, "audio focus lost"),
    DiaryEntry(at(239), PlaybackEvent.UNSUPPRESSED),
    DiaryEntry(at(140), PlaybackEvent.ERROR, "source error 2004"),
    DiaryEntry(at(139), PlaybackEvent.PLAYING),
    DiaryEntry(at(48), PlaybackDiary.PROCESS_STARTED),
    DiaryEntry(at(47), PlaybackEvent.PLAY_REQUESTED, "The Lighthouse Wakes"),
    DiaryEntry(at(46), PlaybackEvent.PLAYING),
    DiaryEntry(at(12), PlaybackEvent.PAUSED),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackRecordPreview(empty: Boolean) {
    val entries = if (empty) emptyList() else ENTRIES
    val summary = PlaybackRecord.summarise(entries, PlaybackRecord.startOfDay(NOW_MS))
    val days = PlaybackRecord.read(entries, NOW_MS)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Why playback stopped") },
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SummaryCardPreview(summary = summary, isEmpty = days.isEmpty()) }
            item {
                Text(
                    "This record stays on this phone. Nothing here is sent anywhere " +
                        "unless you send it yourself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(onClick = {}, enabled = days.isNotEmpty()) { Text("Copy") }
                    TextButton(onClick = {}) { Text("Send feedback") }
                    TextButton(onClick = {}, enabled = days.isNotEmpty()) { Text("Clear the record") }
                }
            }
            days.forEach { day ->
                item(key = "day-${day.label}") {
                    Text(
                        day.label,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(day.lines) { line -> RecordRowPreview(line) }
            }
        }
    }
}

@Composable
private fun SummaryCardPreview(summary: PlaybackRecordSummary, isEmpty: Boolean) {
    val sentence = summary.sentence("today")
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                when {
                    isEmpty -> "Nothing has been recorded yet."
                    sentence == null -> "Nothing has interrupted playback today."
                    else -> sentence
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (!isEmpty) {
                Text(
                    "Counted from the lines below. A stop that lugu chose — a pause, " +
                        "the end of a book — is not counted here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecordRowPreview(line: RecordLine) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            TIME_FORMAT.format(Date(line.entry.atMs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                line.entry.detail?.takeIf { it.isNotBlank() }
                    ?.let { "${line.entry.event} — $it" }
                    ?: line.entry.event,
                style = MaterialTheme.typography.bodyMedium,
            )
            line.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * The zone is set on the formatter rather than inherited from the default, which is
 * captured when a [SimpleDateFormat] is constructed — here, before the rule that pins it.
 */
private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.UK).apply {
    timeZone = TimeZone.getTimeZone("Europe/London")
}
