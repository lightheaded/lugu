package io.github.lightheaded.lugu.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.lightheaded.lugu.core.model.ListeningStats
import io.github.lightheaded.lugu.core.model.ListeningStatsCalculator
import io.github.lightheaded.lugu.core.model.LocalDayIndex
import io.github.lightheaded.lugu.core.model.SessionPoint
import io.github.lightheaded.lugu.core.model.TitleTotal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
 * Catches the stats screen losing the parts that carry its meaning.
 *
 * Three of them are not asserted on anywhere else, and each would leave every other test
 * green if it broke: the bar chart keeping its shape at every height, a quiet day still
 * drawing as a day rather than as a gap, and the empty state saying why it is empty.
 *
 * Unlike `PlaybackRecordScreenshotTest` this photographs the real [StatsContent] rather
 * than a copy of the layout. That is why [StatsScreen] is split in two — the copy in the
 * other test is a second thing to keep in step, and it is not worth repeating.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    // The locale is part of the qualifier, not a JVM default. The chart reads its locale
    // from the composition, so only the device configuration decides the weekday names.
    // Prepended, not appended: resource qualifiers have a fixed order and the locale comes
    // near the front, so "…-nonav-en-rGB" is rejected outright.
    qualifiers = "en-rGB-" + RobolectricDeviceQualifiers.Pixel5,
    // A plain Application, for the same reason the playback record test gives: lugu's own
    // builds the whole Hilt graph, which reaches the encrypted token store and asks for an
    // AndroidKeyStore algorithm Robolectric does not have.
    application = Application::class,
)
class StatsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var originalZone: TimeZone
    private lateinit var originalLocale: Locale

    /**
     * Both are pinned, and both change the picture.
     *
     * The zone decides which day each session falls in. The JVM locale decides the digits
     * in every length, because `formatLength` goes through `String.format`. The weekday
     * names come from the *configuration* locale in the qualifier above instead, because
     * the chart reads its locale from the composition. Either one left to the machine makes
     * this baseline a property of whoever recorded it.
     */
    @Before
    fun pinZoneAndLocale() {
        originalZone = TimeZone.getDefault()
        originalLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE))
        Locale.setDefault(Locale.UK)
    }

    @After
    fun restoreZoneAndLocale() {
        TimeZone.setDefault(originalZone)
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `the stats lead with the total and the fortnight`() {
        capture("stats_light", dark = false) {
            StatsContent(state = StatsUiState(stats = busyFortnight()), onBack = {})
        }
    }

    @Test
    fun `the stats read correctly in the dark`() {
        capture("stats_dark", dark = true) {
            StatsContent(state = StatsUiState(stats = busyFortnight()), onBack = {})
        }
    }

    /** An empty ledger has to say why it is empty, or it reads as a screen that failed. */
    @Test
    fun `an empty ledger says why it is empty`() {
        capture("stats_empty_light", dark = false) {
            StatsContent(state = StatsUiState(stats = emptyLedger()), onBack = {})
        }
    }

    /**
     * One day of listening and nothing else.
     *
     * The case that breaks a chart scaled by its busiest day: every other bar is zero, so
     * the divisor is the only bar with a value. It must draw one full bar and thirteen
     * hairlines, and not one full bar and thirteen holes.
     */
    @Test
    fun `a single day of listening still draws a chart`() {
        capture("stats_one_day_light", dark = false) {
            StatsContent(state = StatsUiState(stats = oneDayOnly()), onBack = {})
        }
    }

    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { ScreenshotTheme(dark = dark, content = content) }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    /** Built through the real calculator, so the picture and the arithmetic cannot drift apart. */
    private fun summarise(points: List<SessionPoint>, titles: List<TitleTotal>): ListeningStats {
        val zone = ZoneId.of(ZONE)
        // The same mapping StatsRepository uses, including the API 26 spelling of it.
        val dayOf = LocalDayIndex { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() }
        return ListeningStatsCalculator.summarise(
            points = points,
            titles = titles,
            bookSeconds = points.sumOf { it.secondsListened } * 0.8,
            podcastSeconds = points.sumOf { it.secondsListened } * 0.2,
            today = dayOf.of(NOW_MS),
            days = dayOf,
        )
    }

    /**
     * A fortnight that looks like real listening: a long commute most days, two days
     * missed, and one Sunday of five hours. The missed days are the point — a chart with
     * no gaps in it does not prove the gaps are drawn.
     */
    private fun busyFortnight(): ListeningStats {
        val minutesPerDayAgo = mapOf(
            0 to 55, 1 to 40, 2 to 75, 3 to 0, 4 to 65, 5 to 50, 6 to 300,
            7 to 45, 8 to 60, 9 to 0, 10 to 35, 11 to 70, 12 to 55, 13 to 80,
        )
        val points = minutesPerDayAgo.filterValues { it > 0 }.map { (daysAgo, minutes) ->
            SessionPoint(
                startedAtMs = NOW_MS - daysAgo * DAY_MS + 3 * HOUR_MS,
                secondsListened = minutes * 60.0,
            )
        }
        return summarise(
            points = points,
            titles = listOf(
                TitleTotal("li_1", "Lighthouse Wakes", "James T. R. Corven", 21_600.0),
                TitleTotal("li_2", "The Breakwater", "Jefferson Vale", 14_400.0),
                TitleTotal("li_3", "Riverton Dawn", "Nessa Cardrow", 9_000.0),
                TitleTotal("li_4", "The Tidelands", "The Tidelands", 4_800.0),
            ),
        )
    }

    private fun oneDayOnly(): ListeningStats = summarise(
        points = listOf(SessionPoint(NOW_MS - 4 * DAY_MS + 3 * HOUR_MS, 5_400.0)),
        titles = listOf(TitleTotal("li_1", "Lighthouse Wakes", "James T. R. Corven", 5_400.0)),
    )

    private fun emptyLedger(): ListeningStats = summarise(points = emptyList(), titles = emptyList())

    private companion object {
        const val ZONE = "Europe/London"

        /** Saturday 14 March 2026, 09:00 London. Fixed so the weekday labels never move. */
        const val NOW_MS = 1_773_478_800_000L

        const val DAY_MS = 86_400_000L
        const val HOUR_MS = 3_600_000L
    }
}

/**
 * lugu's palette without dynamic colour, matching `PlaybackRecordScreenshotTest`.
 *
 * The app prefers the wallpaper's colours from Android 12 onwards, which are not the same
 * on two phones and so cannot be a baseline.
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
