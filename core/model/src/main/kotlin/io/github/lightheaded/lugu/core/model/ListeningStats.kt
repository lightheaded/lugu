package io.github.lightheaded.lugu.core.model

/**
 * What the session ledger adds up to.
 *
 * The ledger has recorded every session since 15 August and no screen has ever read it.
 * This is the arithmetic that turns it into something a person can look at.
 *
 * ## Why no calendar type appears here
 *
 * `:core:model` is KMP-ready by contract — pure Kotlin, no Android and no JVM-only type.
 * `java.time` is JVM-only, so a day is an **epoch day number** here: a `Long` that
 * increases by one at each local midnight. Whoever calls this decides what a day means and
 * supplies [LocalDayIndex]; a test supplies a fixed-width fake and gets the same answers.
 *
 * That split is not only about KMP. Day boundaries are the part of this that is easy to
 * get wrong and hard to see wrong, so the arithmetic is testable without a time zone
 * anywhere near it.
 */

/** One session, reduced to what the arithmetic needs. */
data class SessionPoint(
    val startedAtMs: Long,
    val secondsListened: Double,
)

/** Maps an instant to the local day it falls in, counted in days from any fixed origin. */
fun interface LocalDayIndex {
    fun of(instantMs: Long): Long
}

/** One day of the chart. [day] is an epoch day number, so gaps are filled, not skipped. */
data class DayTotal(
    val day: Long,
    val secondsListened: Double,
)

/** One item, and everything listened to it across every session. */
data class TitleTotal(
    val libraryItemId: String,
    val title: String,
    val author: String,
    val secondsListened: Double,
)

data class ListeningStats(
    val totalSeconds: Double,
    val sessionCount: Int,
    val last7Seconds: Double,
    val last30Seconds: Double,
    /** Oldest first, one entry per day with no gaps, so a chart can be drawn straight from it. */
    val recentDays: List<DayTotal>,
    val currentStreakDays: Int,
    val longestStreakDays: Int,
    val daysListened: Int,
    val topTitles: List<TitleTotal>,
    val bookSeconds: Double,
    val podcastSeconds: Double,
) {
    val hasAnything: Boolean get() = totalSeconds > 0.0

    /** The day with the most listening, for scaling a chart. Zero when nothing was heard. */
    val busiestDaySeconds: Double get() = recentDays.maxOfOrNull { it.secondsListened } ?: 0.0
}

object ListeningStatsCalculator {

    /** How many days the chart shows. Fourteen fits a phone without a horizontal scroll. */
    const val CHART_DAYS: Int = 14

    /**
     * Adds up [points] and answers with everything the stats screen shows.
     *
     * [today] is the epoch day number of now, from the same [days] mapping as the points.
     * Passing it rather than reading a clock is what makes "the streak ends when yesterday
     * is empty too" testable.
     *
     * [titles] and [bookSeconds]/[podcastSeconds] come from grouped SQL rather than from
     * [points], because grouping by a string in Kotlin over every session ever recorded is
     * work the database already does better.
     */
    fun summarise(
        points: List<SessionPoint>,
        titles: List<TitleTotal>,
        bookSeconds: Double,
        podcastSeconds: Double,
        today: Long,
        days: LocalDayIndex,
        chartDays: Int = CHART_DAYS,
    ): ListeningStats {
        val byDay = HashMap<Long, Double>()
        var total = 0.0
        for (point in points) {
            val seconds = point.secondsListened.coerceAtLeast(0.0)
            total += seconds
            if (seconds <= 0.0) continue
            val day = days.of(point.startedAtMs)
            byDay[day] = (byDay[day] ?: 0.0) + seconds
        }

        val chartStart = today - (chartDays - 1)
        val recentDays = (chartStart..today).map { DayTotal(it, byDay[it] ?: 0.0) }

        return ListeningStats(
            totalSeconds = total,
            sessionCount = points.size,
            last7Seconds = sumOfDays(byDay, from = today - 6, to = today),
            last30Seconds = sumOfDays(byDay, from = today - 29, to = today),
            recentDays = recentDays,
            currentStreakDays = currentStreak(byDay.keys, today),
            longestStreakDays = longestStreak(byDay.keys),
            daysListened = byDay.size,
            topTitles = titles,
            bookSeconds = bookSeconds,
            podcastSeconds = podcastSeconds,
        )
    }

    private fun sumOfDays(byDay: Map<Long, Double>, from: Long, to: Long): Double {
        var sum = 0.0
        for (day in from..to) sum += byDay[day] ?: 0.0
        return sum
    }

    /**
     * Days listened in an unbroken run up to now.
     *
     * **A today with nothing in it does not end the run.** The alternative makes the number
     * fall to zero at every midnight and climb back in the evening, which reads as lost
     * data rather than as a day not started. So the run is counted from today when today
     * has listening, and from yesterday when it does not. Two empty days end it.
     */
    private fun currentStreak(listened: Set<Long>, today: Long): Int {
        var cursor = when {
            listened.contains(today) -> today
            listened.contains(today - 1) -> today - 1
            else -> return 0
        }
        var run = 0
        while (listened.contains(cursor)) {
            run += 1
            cursor -= 1
        }
        return run
    }

    private fun longestStreak(listened: Set<Long>): Int {
        if (listened.isEmpty()) return 0
        var best = 0
        for (day in listened) {
            // Count a run only from its first day, so each run is walked once.
            if (listened.contains(day - 1)) continue
            var run = 0
            var cursor = day
            while (listened.contains(cursor)) {
                run += 1
                cursor += 1
            }
            if (run > best) best = run
        }
        return best
    }
}
