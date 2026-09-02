package io.github.lightheaded.lugu.core.sync

import io.github.lightheaded.lugu.core.db.SessionLedgerDao
import io.github.lightheaded.lugu.core.model.ListeningStats
import io.github.lightheaded.lugu.core.model.ListeningStatsCalculator
import io.github.lightheaded.lugu.core.model.LocalDayIndex
import io.github.lightheaded.lugu.core.model.SessionPoint
import io.github.lightheaded.lugu.core.model.TitleTotal
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Turns the session ledger into the numbers the stats screen shows.
 *
 * The arithmetic is not here. It is in [ListeningStatsCalculator], which knows nothing
 * about time zones and can be tested without one. What this class adds is the two things
 * that need a real calendar and a real database: the day mapping, and the grouped queries.
 *
 * ## The zone is read on every emission
 *
 * [ZoneId.systemDefault] is called each time the numbers are rebuilt rather than held in a
 * field. Somebody who flies between two zones must see their days redrawn on the boundary
 * their phone now uses. A cached zone would keep yesterday's midnight until the process
 * restarted, and a streak that breaks because of a flight is a bug that only ever appears
 * to travellers.
 *
 * ## How many books were finished is not here
 *
 * It reads naturally beside "hours listened", and it is not in the ledger. Finished is a
 * flag on the progress row, so it belongs to a query over `progress` rather than to a
 * count of sessions. Left out rather than approximated: counting a session that reached
 * the end of its duration would report a book abandoned at 98% as finished and would miss
 * one marked finished by hand.
 */
@Singleton
class StatsRepository @Inject constructor(
    private val dao: SessionLedgerDao,
    private val clock: Clock,
) {
    fun observe(account: ActiveAccount): Flow<ListeningStats> = combine(
        dao.observeSessionPoints(account.serverId, account.userId),
        dao.observeTitleTotals(account.serverId, account.userId, TOP_TITLES),
        dao.observeMediaTypeTotals(account.serverId, account.userId),
    ) { points, titles, mediaTotals ->
        val zone = ZoneId.systemDefault()
        ListeningStatsCalculator.summarise(
            points = points.map { SessionPoint(it.startedAtMs, it.timeListeningSec) },
            titles = titles.map {
                TitleTotal(
                    libraryItemId = it.libraryItemId,
                    title = it.title,
                    author = it.author,
                    secondsListened = it.secondsListened,
                )
            },
            bookSeconds = mediaTotals.firstOrNull { it.mediaType == BOOK }?.secondsListened ?: 0.0,
            podcastSeconds = mediaTotals.firstOrNull { it.mediaType == PODCAST }?.secondsListened ?: 0.0,
            today = dayOf(clock.nowMs(), zone),
            days = LocalDayIndex { dayOf(it, zone) },
        )
    }

    /**
     * `Instant.atZone().toLocalDate()` and not `LocalDate.ofInstant`.
     *
     * The two say the same thing, and the second one is API 34. `minSdk` is 26, so it
     * would have crashed on every device below Android 14 — caught by lint, which is why
     * a lint error in this project is a work item rather than noise.
     */
    private fun dayOf(instantMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(instantMs).atZone(zone).toLocalDate().toEpochDay()

    private companion object {
        /**
         * Five rows of "most listened". Enough to recognise the shape of a year and short
         * enough that the list does not become the screen.
         */
        const val TOP_TITLES = 5

        // The server's own media type strings, lowercased by the query.
        const val BOOK = "book"
        const val PODCAST = "podcast"
    }
}
