package io.github.lightheaded.lugu.core.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The per-podcast summary that orders the library grid by latest episode.
 *
 * The newest date must come from the newest episode, not from the last row written or the
 * highest position, and the summary must stay inside one account: two accounts on one phone
 * can mirror the same podcast id from different servers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpisodeSummaryTest {

    private lateinit var db: LuguDatabase
    private val serverId = "https://pods.example#u1"
    private val userId = "u1"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LuguDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun episode(
        id: String,
        itemId: String,
        publishedAtMs: Long,
        position: Int = 0,
        server: String = serverId,
    ) = EpisodeEntity(
        serverId = server,
        userId = userId,
        id = id,
        libraryItemId = itemId,
        title = "Episode $id",
        subtitle = null,
        description = null,
        episodeNumber = null,
        season = null,
        publishedAtMs = publishedAtMs,
        durationSec = 1_800.0,
        position = position,
    )

    @Test
    fun `each podcast gets its count and its newest publish date`() = runTest {
        db.episodeDao().upsertAll(
            listOf(
                // Written newest-first and with positions that disagree with the dates, so a
                // query that read either of those instead of the date would fail here.
                episode("a2", "pod-a", publishedAtMs = 500, position = 0),
                episode("a1", "pod-a", publishedAtMs = 900, position = 1),
                episode("a3", "pod-a", publishedAtMs = 100, position = 2),
                episode("b1", "pod-b", publishedAtMs = 300),
            ),
        )

        val summaries = db.episodeDao().summaries(serverId, userId).associateBy { it.libraryItemId }

        assertThat(summaries.getValue("pod-a").episodeCount).isEqualTo(3)
        assertThat(summaries.getValue("pod-a").latestPublishedAtMs).isEqualTo(900)
        assertThat(summaries.getValue("pod-b").episodeCount).isEqualTo(1)
        assertThat(summaries.getValue("pod-b").latestPublishedAtMs).isEqualTo(300)
    }

    @Test
    fun `another account's episodes do not count`() = runTest {
        db.episodeDao().upsertAll(
            listOf(
                episode("a1", "pod-a", publishedAtMs = 100),
                episode("x1", "pod-a", publishedAtMs = 999, server = "https://other.example#u1"),
            ),
        )

        val summary = db.episodeDao().observeSummaries(serverId, userId).first().single()

        assertThat(summary.episodeCount).isEqualTo(1)
        assertThat(summary.latestPublishedAtMs).isEqualTo(100)
    }
}
