package io.github.lightheaded.lugu.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ListeningStatsCalculatorTest {

    /**
     * A fixed-width day, so a test says what it means without a time zone.
     *
     * The real mapping comes from `java.time` on the Android side and is the part that
     * knows about summer time. What is checked here is the arithmetic on top of it, and
     * that arithmetic only requires consecutive days to differ by one.
     */
    private val days = LocalDayIndex { it / DAY_MS }

    /** Day 100 at noon, so a test can move a few hours either way without changing day. */
    private val today = 100L
    private fun at(day: Long, hour: Int = 12) = day * DAY_MS + hour * 3_600_000L

    private fun summarise(
        points: List<SessionPoint>,
        titles: List<TitleTotal> = emptyList(),
        bookSeconds: Double = 0.0,
        podcastSeconds: Double = 0.0,
        chartDays: Int = ListeningStatsCalculator.CHART_DAYS,
    ) = ListeningStatsCalculator.summarise(
        points = points,
        titles = titles,
        bookSeconds = bookSeconds,
        podcastSeconds = podcastSeconds,
        today = today,
        days = days,
        chartDays = chartDays,
    )

    @Test
    fun `nothing recorded reads as nothing rather than as zero everywhere`() {
        val stats = summarise(emptyList())

        assertThat(stats.hasAnything).isFalse()
        assertThat(stats.totalSeconds).isEqualTo(0.0)
        assertThat(stats.currentStreakDays).isEqualTo(0)
        assertThat(stats.longestStreakDays).isEqualTo(0)
        assertThat(stats.daysListened).isEqualTo(0)
        // The chart still has its days, so an empty screen has a shape rather than a gap.
        assertThat(stats.recentDays).hasSize(ListeningStatsCalculator.CHART_DAYS)
        assertThat(stats.busiestDaySeconds).isEqualTo(0.0)
    }

    @Test
    fun `every session adds to the total`() {
        val stats = summarise(
            listOf(
                SessionPoint(at(today), 1_200.0),
                SessionPoint(at(today - 1), 600.0),
                SessionPoint(at(today - 40), 3_000.0),
            ),
        )

        assertThat(stats.totalSeconds).isEqualTo(4_800.0)
        assertThat(stats.sessionCount).isEqualTo(3)
    }

    /**
     * A session opened and abandoned holds zero seconds, and there are many of them — a
     * tap on the wrong row makes one. Counting it as a day listened would turn a misfire
     * into a streak.
     */
    @Test
    fun `a session with no listening in it does not make a day count`() {
        val stats = summarise(listOf(SessionPoint(at(today), 0.0)))

        assertThat(stats.daysListened).isEqualTo(0)
        assertThat(stats.currentStreakDays).isEqualTo(0)
        assertThat(stats.hasAnything).isFalse()
        // It is still a session that happened, so the count keeps it.
        assertThat(stats.sessionCount).isEqualTo(1)
    }

    @Test
    fun `the chart holds one entry per day and fills the gaps`() {
        val stats = summarise(
            listOf(
                SessionPoint(at(today), 100.0),
                SessionPoint(at(today - 3), 200.0),
            ),
            chartDays = 5,
        )

        assertThat(stats.recentDays.map { it.day }).isEqualTo(listOf(96L, 97L, 98L, 99L, 100L))
        assertThat(stats.recentDays.map { it.secondsListened })
            .isEqualTo(listOf(0.0, 200.0, 0.0, 0.0, 100.0))
        assertThat(stats.busiestDaySeconds).isEqualTo(200.0)
    }

    @Test
    fun `two sessions on one day are added together`() {
        val stats = summarise(
            listOf(
                SessionPoint(at(today, hour = 8), 300.0),
                SessionPoint(at(today, hour = 21), 900.0),
            ),
            chartDays = 1,
        )

        assertThat(stats.recentDays.single().secondsListened).isEqualTo(1_200.0)
        assertThat(stats.daysListened).isEqualTo(1)
    }

    @Test
    fun `the last seven days reach back six and stop`() {
        val stats = summarise(
            listOf(
                SessionPoint(at(today), 10.0),
                SessionPoint(at(today - 6), 20.0),
                SessionPoint(at(today - 7), 40.0),
            ),
        )

        assertThat(stats.last7Seconds).isEqualTo(30.0)
        assertThat(stats.last30Seconds).isEqualTo(70.0)
    }

    /**
     * The ledger records when a session started and how long was heard in it, and no
     * timeline in between. So a session that runs past midnight belongs to the day it
     * began. Splitting it would invent detail the row does not hold.
     */
    @Test
    fun `a session that runs past midnight counts on the day it began`() {
        val stats = summarise(
            listOf(SessionPoint(at(today - 1, hour = 23), 7_200.0)),
            chartDays = 2,
        )

        assertThat(stats.recentDays.map { it.secondsListened }).isEqualTo(listOf(7_200.0, 0.0))
    }

    @Test
    fun `the current streak counts back from today`() {
        val stats = summarise(
            (0..4).map { SessionPoint(at(today - it), 60.0) },
        )

        assertThat(stats.currentStreakDays).isEqualTo(5)
    }

    /**
     * The decision this test exists to hold: a day that has not been listened to *yet* is
     * not a broken streak. Without it the number falls to zero at every midnight and
     * climbs back in the evening, which reads as lost data.
     */
    @Test
    fun `a today with nothing in it does not end the streak`() {
        val stats = summarise(
            (1..3).map { SessionPoint(at(today - it), 60.0) },
        )

        assertThat(stats.currentStreakDays).isEqualTo(3)
    }

    @Test
    fun `two empty days end the streak`() {
        val stats = summarise(
            (2..4).map { SessionPoint(at(today - it), 60.0) },
        )

        assertThat(stats.currentStreakDays).isEqualTo(0)
    }

    @Test
    fun `the longest streak is found wherever it is`() {
        val history = listOf(20L, 21L, 22L, 23L, 30L, 31L, 99L, 100L)
        val stats = summarise(history.map { SessionPoint(at(it), 60.0) })

        assertThat(stats.longestStreakDays).isEqualTo(4)
        assertThat(stats.currentStreakDays).isEqualTo(2)
        assertThat(stats.daysListened).isEqualTo(8)
    }

    @Test
    fun `one day of listening is a streak of one`() {
        val stats = summarise(listOf(SessionPoint(at(today), 60.0)))

        assertThat(stats.currentStreakDays).isEqualTo(1)
        assertThat(stats.longestStreakDays).isEqualTo(1)
    }

    /** Grouped totals come from SQL, so the calculator must pass them through untouched. */
    @Test
    fun `titles and the media split are carried through as given`() {
        val titles = listOf(TitleTotal("li_1", "Lighthouse Wakes", "Corven", 4_000.0))
        val stats = summarise(
            listOf(SessionPoint(at(today), 4_000.0)),
            titles = titles,
            bookSeconds = 3_000.0,
            podcastSeconds = 1_000.0,
        )

        assertThat(stats.topTitles).isEqualTo(titles)
        assertThat(stats.bookSeconds).isEqualTo(3_000.0)
        assertThat(stats.podcastSeconds).isEqualTo(1_000.0)
    }

    /** A negative delta would mean a bug upstream, and it must not eat real listening. */
    @Test
    fun `a negative delta cannot reduce the total`() {
        val stats = summarise(
            listOf(
                SessionPoint(at(today), 100.0),
                SessionPoint(at(today), -50.0),
            ),
        )

        assertThat(stats.totalSeconds).isEqualTo(100.0)
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
