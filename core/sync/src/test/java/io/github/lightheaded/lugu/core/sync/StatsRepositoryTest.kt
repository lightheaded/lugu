package io.github.lightheaded.lugu.core.sync

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.db.MediaTypeTotalRow
import io.github.lightheaded.lugu.core.db.SessionLedgerDao
import io.github.lightheaded.lugu.core.db.SessionLedgerEntity
import io.github.lightheaded.lugu.core.db.SessionPointRow
import io.github.lightheaded.lugu.core.db.TitleTotalRow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * What [StatsRepository] adds on top of the arithmetic: a real calendar, and the picking
 * apart of the grouped rows.
 *
 * The arithmetic itself is covered in `ListeningStatsCalculatorTest` with a fixed-width
 * fake day. This is the other half — that a day means the listener's local day and not
 * UTC. The zone here is deliberately one with a large offset, because a mistake of this
 * kind is invisible in London in winter and wrong by a whole day in Auckland.
 */
class StatsRepositoryTest {

    private lateinit var originalZone: TimeZone

    private val zone = ZoneId.of("Pacific/Auckland")

    @Before
    fun pinZone() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    /** An instant from a local wall-clock reading in the pinned zone. */
    private fun localMs(date: String, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(LocalDate.parse(date), java.time.LocalTime.of(hour, minute))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun epochDay(date: String): Long = LocalDate.parse(date).toEpochDay()

    private fun repository(
        points: List<SessionPointRow> = emptyList(),
        titles: List<TitleTotalRow> = emptyList(),
        mediaTotals: List<MediaTypeTotalRow> = emptyList(),
        nowMs: Long,
    ) = StatsRepository(
        dao = FakeLedgerDao(points, titles, mediaTotals),
        clock = object : Clock {
            override fun nowMs(): Long = nowMs
        },
    )

    /**
     * The failure this pins. 23:30 on 12 March in Auckland is 10:30 UTC on the *same*
     * date, but 00:30 on 13 March in Auckland is 11:30 UTC on the 12th. A day taken from
     * UTC would file the second session under the day before the listener's own.
     */
    @Test
    fun `a session late in the local evening counts on the local day`() = runTest {
        val repository = repository(
            points = listOf(
                SessionPointRow(localMs("2026-03-12", hour = 23, minute = 30), 600.0),
                SessionPointRow(localMs("2026-03-13", hour = 0, minute = 30), 900.0),
            ),
            nowMs = localMs("2026-03-13", hour = 9),
        )

        val stats = repository.observe(account).first()

        val byDay = stats.recentDays.associate { it.day to it.secondsListened }
        assertThat(byDay[epochDay("2026-03-12")]).isEqualTo(600.0)
        assertThat(byDay[epochDay("2026-03-13")]).isEqualTo(900.0)
        // Two calendar days running, which is what the listener would call a streak of two.
        assertThat(stats.currentStreakDays).isEqualTo(2)
    }

    @Test
    fun `the chart ends on today in the listener's own zone`() = runTest {
        val repository = repository(nowMs = localMs("2026-03-13", hour = 9))

        val stats = repository.observe(account).first()

        assertThat(stats.recentDays.last().day).isEqualTo(epochDay("2026-03-13"))
    }

    @Test
    fun `books and podcasts are told apart, and an unknown type is neither`() = runTest {
        val repository = repository(
            mediaTotals = listOf(
                MediaTypeTotalRow("book", 5_000.0),
                MediaTypeTotalRow("podcast", 1_500.0),
                MediaTypeTotalRow("something-else", 99.0),
            ),
            nowMs = localMs("2026-03-13", hour = 9),
        )

        val stats = repository.observe(account).first()

        assertThat(stats.bookSeconds).isEqualTo(5_000.0)
        assertThat(stats.podcastSeconds).isEqualTo(1_500.0)
    }

    @Test
    fun `a ledger with no podcast rows reports no podcast time rather than failing`() = runTest {
        val repository = repository(
            mediaTotals = listOf(MediaTypeTotalRow("book", 5_000.0)),
            nowMs = localMs("2026-03-13", hour = 9),
        )

        val stats = repository.observe(account).first()

        assertThat(stats.podcastSeconds).isEqualTo(0.0)
    }

    @Test
    fun `the grouped titles come through in the order the query gave them`() = runTest {
        val repository = repository(
            titles = listOf(
                TitleTotalRow("li_2", "The Breakwater", "Jefferson Vale", 9_000.0),
                TitleTotalRow("li_1", "Lighthouse Wakes", "James T. R. Corven", 4_000.0),
            ),
            nowMs = localMs("2026-03-13", hour = 9),
        )

        val stats = repository.observe(account).first()

        assertThat(stats.topTitles.map { it.title })
            .isEqualTo(listOf("The Breakwater", "Lighthouse Wakes"))
        assertThat(stats.topTitles.first().secondsListened).isEqualTo(9_000.0)
    }

    private val account = ActiveAccount(
        serverId = "https://books.example#user-1",
        baseUrl = "https://books.example",
        userId = "user-1",
        username = "listener",
        defaultLibraryId = "lib-1",
    )
}

/**
 * The ledger reduced to the three queries the stats screen reads.
 *
 * A fake rather than an in-memory database on purpose: the SQL is already proven by Room's
 * own compile-time check and by the migration tests, and what is under test here is the
 * calendar. Adding Robolectric to `:core:sync` to reach it would buy nothing.
 */
private class FakeLedgerDao(
    private val points: List<SessionPointRow>,
    private val titles: List<TitleTotalRow>,
    private val mediaTotals: List<MediaTypeTotalRow>,
) : SessionLedgerDao {

    override fun observeSessionPoints(serverId: String, userId: String): Flow<List<SessionPointRow>> =
        flowOf(points)

    override fun observeTitleTotals(
        serverId: String,
        userId: String,
        limit: Int,
    ): Flow<List<TitleTotalRow>> = flowOf(titles.take(limit))

    override fun observeMediaTypeTotals(
        serverId: String,
        userId: String,
    ): Flow<List<MediaTypeTotalRow>> = flowOf(mediaTotals)

    override fun observeRecent(
        serverId: String,
        userId: String,
        limit: Int,
    ): Flow<List<SessionLedgerEntity>> = flowOf(emptyList())

    override suspend fun byId(id: String): SessionLedgerEntity? = null

    override suspend fun pendingUploads(serverId: String, userId: String): List<SessionLedgerEntity> =
        emptyList()

    override suspend fun upsert(session: SessionLedgerEntity) = Unit

    override suspend fun markUploaded(ids: List<String>) = Unit
}
