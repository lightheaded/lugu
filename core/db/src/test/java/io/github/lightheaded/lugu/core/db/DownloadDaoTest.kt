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
 * The pending-delete state, exercised against real SQLite.
 *
 * A download marked pending-delete has to disappear from every screen that reads
 * [DownloadDao.observeAll] or [DownloadDao.observeForItem], and it has to disappear from
 * [DownloadDao.unfinished] too, or the engine's own reconciler folds its still-complete
 * files straight back to `completed` and silently undoes the delete. [DownloadDao.get]
 * has to keep seeing it regardless, because that is how a delete is undone or finalised.
 * None of that is visible to the Kotlin compiler — it lives in the `WHERE` clauses — so
 * it is asserted here rather than only read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadDaoTest {

    private lateinit var db: LuguDatabase
    private lateinit var dao: DownloadDao
    private val serverId = "s"
    private val userId = "u"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            LuguDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.downloadDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a pending-delete row is left out of observeAll`() = runTest {
        dao.upsert(download("a", DownloadState.COMPLETED))
        dao.upsert(download("b", DownloadState.PENDING_DELETE))

        assertThat(dao.observeAll(serverId, userId).first().map { it.libraryItemId }).containsExactly("a")
    }

    @Test
    fun `a pending-delete row is left out of observeForItem`() = runTest {
        dao.upsert(download("pod", DownloadState.COMPLETED, episodeKey = "ep1"))
        dao.upsert(download("pod", DownloadState.PENDING_DELETE, episodeKey = "ep2"))

        assertThat(dao.observeForItem(serverId, userId, "pod").first().map { it.episodeKey })
            .containsExactly("ep1")
    }

    /**
     * The one query that must not see a pending-delete row at all: the engine reconciles
     * every row this returns against Media3's index, and every file behind a
     * pending-delete row is still complete there, so a row that leaked through here would
     * be folded straight back to `completed` on the next tick.
     */
    @Test
    fun `unfinished excludes pending-delete alongside completed`() = runTest {
        dao.upsert(download("a", DownloadState.QUEUED))
        dao.upsert(download("b", DownloadState.DOWNLOADING))
        dao.upsert(download("c", DownloadState.FAILED))
        dao.upsert(download("d", DownloadState.COMPLETED))
        dao.upsert(download("e", DownloadState.PENDING_DELETE))

        assertThat(dao.unfinished().map { it.libraryItemId }).containsExactly("a", "b", "c")
    }

    @Test
    fun `get still finds a pending-delete row`() = runTest {
        dao.upsert(download("a", DownloadState.PENDING_DELETE))

        assertThat(dao.get(serverId, userId, "a", "")?.state).isEqualTo(DownloadState.PENDING_DELETE)
    }

    @Test
    fun `pendingDeleteBytes sums only pending-delete rows`() = runTest {
        dao.upsert(download("a", DownloadState.PENDING_DELETE, bytesDownloaded = 1_000L))
        dao.upsert(download("b", DownloadState.PENDING_DELETE, bytesDownloaded = 2_000L))
        dao.upsert(download("c", DownloadState.COMPLETED, bytesDownloaded = 5_000L))

        assertThat(dao.pendingDeleteBytes()).isEqualTo(3_000L)
    }

    @Test
    fun `pendingDeleteBytes is nought with nothing pending`() = runTest {
        dao.upsert(download("a", DownloadState.COMPLETED, bytesDownloaded = 5_000L))

        assertThat(dao.pendingDeleteBytes()).isEqualTo(0L)
    }

    private fun download(
        itemId: String,
        state: String,
        episodeKey: String = "",
        bytesDownloaded: Long = 0,
    ) = DownloadEntity(
        serverId = serverId,
        userId = userId,
        libraryItemId = itemId,
        episodeKey = episodeKey,
        title = "Title $itemId",
        author = null,
        mediaType = "BOOK",
        state = state,
        tracksJson = """{"tracks":[]}""",
        durationSec = 0.0,
        bytesTotal = bytesDownloaded,
        bytesDownloaded = bytesDownloaded,
        percent = if (state == DownloadState.COMPLETED || state == DownloadState.PENDING_DELETE) 1f else 0.5f,
        requestedAtMs = 0,
        completedAtMs = 0,
        error = null,
    )
}
