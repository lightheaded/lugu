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
 * Continue-listening has to survive podcasts.
 *
 * A book has one progress row; a podcast has one per episode. Joining items to
 * progress on `libraryItemId` alone therefore returns the same podcast once per
 * episode listened to. The UI keys that list by item id, and Compose throws on a
 * duplicate key — so a duplicate row here is an app crash, not a cosmetic glitch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContinueListeningTest {

    private lateinit var db: LuguDatabase
    private val serverId = "https://books.example#u1"
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

    private fun item(
        id: String,
        mediaType: String,
        seriesName: String? = null,
        seriesSequence: Double? = null,
        durationSec: Double = 3_600.0,
    ) = LibraryItemEntity(
        serverId = serverId,
        userId = userId,
        id = id,
        libraryId = "lib1",
        mediaType = mediaType,
        title = "Title $id",
        subtitle = null,
        authorName = null,
        narratorName = null,
        seriesName = seriesName,
        seriesTitle = seriesName,
        seriesSequence = seriesSequence,
        description = null,
        durationSec = durationSec,
        sizeBytes = 0,
        numEpisodes = 0,
        addedAtMs = 0,
        updatedAtMs = 0,
        coverPath = null,
        rawJson = null,
        syncedAtMs = 0,
    )

    private fun progress(itemId: String, episodeId: String?, lastUpdateMs: Long) = ProgressEntity(
        serverId = serverId,
        userId = userId,
        libraryItemId = itemId,
        episodeKey = episodeKeyOf(episodeId),
        currentTimeSec = 120.0,
        durationSec = 3_600.0,
        progress = 0.03,
        isFinished = false,
        lastUpdateMs = lastUpdateMs,
        startedAtMs = 0,
        serverLastUpdateMs = 0,
        isDirty = false,
    )

    @Test
    fun `a podcast with several part-played episodes appears exactly once`() = runTest {
        db.libraryItemDao().upsertAll(listOf(item("li_pod", "PODCAST")))
        db.progressDao().upsertAll(
            listOf(
                progress("li_pod", "ep_1", lastUpdateMs = 100),
                progress("li_pod", "ep_2", lastUpdateMs = 200),
                progress("li_pod", "ep_3", lastUpdateMs = 300),
            ),
        )

        val rows = db.libraryItemDao().observeContinueListening(serverId, userId).first()

        assertThat(rows.map { it.id }).containsExactly("li_pod")
    }

    @Test
    fun `ordering uses the most recent episode of each podcast`() = runTest {
        db.libraryItemDao().upsertAll(listOf(item("li_pod", "PODCAST"), item("li_book", "BOOK")))
        db.progressDao().upsertAll(
            listOf(
                // The podcast's newest episode is more recent than the book.
                progress("li_pod", "ep_1", lastUpdateMs = 100),
                progress("li_pod", "ep_2", lastUpdateMs = 900),
                progress("li_book", null, lastUpdateMs = 500),
            ),
        )

        val rows = db.libraryItemDao().observeContinueListening(serverId, userId).first()

        assertThat(rows.map { it.id }).containsExactly("li_pod", "li_book").inOrder()
    }

    @Test
    fun `finished and unstarted items stay out`() = runTest {
        db.libraryItemDao().upsertAll(listOf(item("li_done", "BOOK"), item("li_new", "BOOK")))
        db.progressDao().upsertAll(
            listOf(
                progress("li_done", null, lastUpdateMs = 100).copy(isFinished = true),
                progress("li_new", null, lastUpdateMs = 200).copy(currentTimeSec = 0.0),
            ),
        )

        assertThat(db.libraryItemDao().observeContinueListening(serverId, userId).first()).isEmpty()
    }

    @Test
    fun `rows from another account are never mixed in`() = runTest {
        db.libraryItemDao().upsertAll(listOf(item("li_a", "BOOK")))
        db.progressDao().upsertAll(listOf(progress("li_a", null, lastUpdateMs = 100)))

        assertThat(db.libraryItemDao().observeContinueListening("other-server", userId).first()).isEmpty()
        assertThat(db.libraryItemDao().observeContinueListening(serverId, "other-user").first()).isEmpty()
    }
}
