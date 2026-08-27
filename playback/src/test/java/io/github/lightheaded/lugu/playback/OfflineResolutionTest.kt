package io.github.lightheaded.lugu.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.api.AuthExpiredException
import io.github.lightheaded.lugu.core.api.DeviceInfoDto
import io.github.lightheaded.lugu.core.api.InMemoryTokenStore
import io.github.lightheaded.lugu.core.api.StaticServerUrlProvider
import io.github.lightheaded.lugu.core.db.ChapterEntity
import io.github.lightheaded.lugu.core.db.DownloadEntity
import io.github.lightheaded.lugu.core.db.DownloadState
import io.github.lightheaded.lugu.core.db.LuguDatabase
import io.github.lightheaded.lugu.core.db.ProgressEntity
import io.github.lightheaded.lugu.core.download.CoverStore
import io.github.lightheaded.lugu.core.download.DownloadCache
import io.github.lightheaded.lugu.core.download.DownloadEngine
import io.github.lightheaded.lugu.core.download.DownloadKeys
import io.github.lightheaded.lugu.core.download.DownloadRepository
import io.github.lightheaded.lugu.core.model.PlayMethod
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.Clock
import io.github.lightheaded.lugu.core.sync.DownloadPrefs
import io.github.lightheaded.lugu.core.sync.LibraryPrefs
import io.github.lightheaded.lugu.core.sync.LibraryRepository
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import io.github.lightheaded.lugu.core.sync.SessionLedgerRepository
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Playing a downloaded book with no server, end to end through the real graph.
 *
 * This is the claim the whole app rests on: the local database is the source of truth, and
 * no surface streams what is already on the phone. `MediaResolver.resolve` is the one place
 * that decides it, so the test builds the real resolver over real Room, a real download
 * repository and a real session ledger. Nothing here is a stand-in for a collaborator.
 *
 * ### Why no server address is configured
 *
 * The client is built over a URL provider that answers null, so every call it can make
 * throws before it opens a socket. That makes the strongest form of the claim assertable:
 * the offline path completes with no server to reach at all. It also makes the other half
 * assertable, because a resolve that reaches the streaming path fails with the client's own
 * [AuthExpiredException]. That exception is the proof the resolver went past the download.
 *
 * ### What a downloaded item resolves to
 *
 * Not to a file path. The URI on each media item is the address the download recorded, and
 * the bytes are found by the custom cache key instead, so a server that has since moved
 * does not orphan a download. The cache key is therefore the thing to assert: it is what
 * makes playback read the phone rather than the network.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineResolutionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val account = ActiveAccount(
        serverId = "server-1",
        baseUrl = "https://server.invalid",
        userId = "user-1",
        username = "listener",
        defaultLibraryId = "library-1",
    )

    private val clock = object : Clock {
        override fun nowMs(): Long = 1_700_000_000_000
    }

    private lateinit var db: LuguDatabase
    private lateinit var resolver: MediaResolver

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LuguDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val deviceInfo = DeviceInfoDto(deviceId = "device-1")
        val client = AbsClient(
            serverUrlProvider = StaticServerUrlProvider(null),
            tokenStore = InMemoryTokenStore(),
            deviceInfo = deviceInfo,
        )
        val okHttp = OkHttpClient()
        val downloadCache = DownloadCache(context = context, okHttpClient = okHttp)
        val downloadPrefs = DownloadPrefs(context = context)

        resolver = MediaResolver(
            context = context,
            client = client,
            progressRepository = ProgressRepository(
                client = client,
                progressDao = db.progressDao(),
                outboxDao = db.outboxDao(),
                positionHistoryDao = db.positionHistoryDao(),
                clock = clock,
            ),
            sessionLedgerRepository = SessionLedgerRepository(
                client = client,
                dao = db.sessionLedgerDao(),
                deviceInfo = deviceInfo,
                clock = clock,
            ),
            libraryRepository = LibraryRepository(
                client = client,
                libraryDao = db.libraryDao(),
                itemDao = db.libraryItemDao(),
                episodeDao = db.episodeDao(),
                chapterDao = db.chapterDao(),
                ftsDao = db.libraryItemFtsDao(),
                seriesDao = db.itemSeriesDao(),
                libraryPrefs = LibraryPrefs(context = context),
                clock = clock,
            ),
            downloadRepository = DownloadRepository(
                context = context,
                client = client,
                downloadDao = db.downloadDao(),
                progressDao = db.progressDao(),
                downloadCache = downloadCache,
                downloadPrefs = downloadPrefs,
                engine = DownloadEngine(
                    context = context,
                    downloadCache = downloadCache,
                    downloadDao = db.downloadDao(),
                    downloadPrefs = downloadPrefs,
                    clock = clock,
                ),
                coverStore = CoverStore(context = context, client = client, okHttpClient = okHttp),
                clock = clock,
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    // region the offline path

    /**
     * The whole point, in one test. A completed download plays with no server configured,
     * and every media item carries the cache key the downloader wrote its bytes under.
     */
    @Test
    fun `a downloaded book resolves with no server to reach`() = runTest {
        seedDownload(state = DownloadState.COMPLETED)

        val resolved = resolver.resolve(account, ITEM, episodeId = null).getOrThrow()

        assertThat(resolved.isOffline).isTrue()
        assertThat(resolved.session.playMethod).isEqualTo(PlayMethod.LOCAL)
        assertThat(resolved.session.title).isEqualTo(TITLE)
        assertThat(resolved.mediaItems.map { it.localConfiguration!!.customCacheKey })
            .containsExactly(
                DownloadKeys.cacheKey(ITEM, "", 0),
                DownloadKeys.cacheKey(ITEM, "", 1),
                DownloadKeys.cacheKey(ITEM, "", 2),
            )
            .inOrder()
    }

    /**
     * The playlist is the book, in book order, from the offsets the download wrote down.
     * The manifest below lists its tracks out of order on purpose, because the order in
     * the file is the order the server sent and is not the order the book plays in.
     */
    @Test
    fun `the playlist is in book order with the recorded addresses`() = runTest {
        seedDownload(state = DownloadState.COMPLETED)

        val resolved = resolver.resolve(account, ITEM, episodeId = null).getOrThrow()

        assertThat(resolved.mediaItems.map { it.localConfiguration!!.uri.toString() })
            .containsExactly(TRACK_0_URL, TRACK_1_URL, TRACK_2_URL)
            .inOrder()
        assertThat(resolved.mediaItems.map { it.mediaId })
            .containsExactly("$ITEM||0", "$ITEM||1", "$ITEM||2")
            .inOrder()
        assertThat(resolved.session.tracks.map { it.startOffsetSec })
            .containsExactly(0.0, 600.0, 1_200.0)
            .inOrder()
    }

    /**
     * Every URI is the address the download wrote, and no address is a fresh one from a
     * play session. Stated as its own test because it is the regression that matters: a
     * resolver that quietly asked the server would still return a playable session.
     */
    @Test
    fun `no address on the offline session came from a play session`() = runTest {
        seedDownload(state = DownloadState.COMPLETED)

        val resolved = resolver.resolve(account, ITEM, episodeId = null).getOrThrow()

        // A play session carries an id, and every offline session must have none.
        assertThat(resolved.session.sessionId).isEmpty()
        assertThat(resolved.mediaItems).hasSize(3)
        resolved.mediaItems.forEach { item ->
            assertThat(item.localConfiguration!!.uri.toString()).startsWith(RECORDED_HOST)
        }
    }

    /** Room holds the position, so the book starts where the phone says and not at zero. */
    @Test
    fun `the offline session starts where Room says`() = runTest {
        seedDownload(state = DownloadState.COMPLETED)
        db.progressDao().upsert(progress(currentTimeSec = 842.5))

        val resolved = resolver.resolve(account, ITEM, episodeId = null).getOrThrow()

        assertThat(resolved.startPositionSec).isEqualTo(842.5)
        assertThat(resolved.session.startTimeSec).isEqualTo(842.5)
    }

    /** Chapters cached with the item are the offline chapters, with no round trip. */
    @Test
    fun `chapters cached with the item reach the offline session`() = runTest {
        seedDownload(state = DownloadState.COMPLETED)
        db.chapterDao().insertAll(
            listOf(
                ChapterEntity(account.serverId, account.userId, ITEM, 0, 0.0, 600.0, "One"),
                ChapterEntity(account.serverId, account.userId, ITEM, 1, 600.0, 1_800.0, "Two"),
            ),
        )

        val resolved = resolver.resolve(account, ITEM, episodeId = null).getOrThrow()

        assertThat(resolved.session.chapters.map { it.title }).containsExactly("One", "Two").inOrder()
    }

    /**
     * An item with no cached chapters still plays. The synthesised list is what the player
     * screen draws, so an empty one would be a book with no timeline at all.
     */
    @Test
    fun `an item with no cached chapters still gets a timeline`() = runTest {
        seedDownload(state = DownloadState.COMPLETED)

        val resolved = resolver.resolve(account, ITEM, episodeId = null).getOrThrow()

        assertThat(resolved.session.chapters).isNotEmpty()
    }

    /**
     * The ledger row is opened with no server session id, which marks it local. That is
     * what gets a week of offline listening replayed to the server later, so it is the
     * offline path's only lasting output and worth asserting on the row itself.
     */
    @Test
    fun `an offline resolve opens a local ledger row`() = runTest {
        seedDownload(state = DownloadState.COMPLETED)

        val resolved = resolver.resolve(account, ITEM, episodeId = null).getOrThrow()
        val row = db.sessionLedgerDao().byId(resolved.ledgerId)!!

        assertThat(row.isLocal).isTrue()
        assertThat(row.isUploaded).isFalse()
        assertThat(row.libraryItemId).isEqualTo(ITEM)
        assertThat(db.sessionLedgerDao().pendingUploads(account.serverId, account.userId).map { it.id })
            .containsExactly(resolved.ledgerId)
    }

    /** A downloaded podcast episode is keyed by its episode id, in Room and in the cache. */
    @Test
    fun `a downloaded episode resolves from its own row`() = runTest {
        db.downloadDao().upsert(
            downloadRow(
                episodeKey = EPISODE,
                state = DownloadState.COMPLETED,
                tracksJson = """{"tracks":[""" + track(0, 0.0, 900.0, TRACK_0_URL, EPISODE) + "]}",
            ),
        )

        val resolved = resolver.resolve(account, ITEM, episodeId = EPISODE).getOrThrow()

        assertThat(resolved.isOffline).isTrue()
        assertThat(resolved.session.episodeId).isEqualTo(EPISODE)
        assertThat(resolved.mediaItems.single().localConfiguration!!.customCacheKey)
            .isEqualTo(DownloadKeys.cacheKey(ITEM, EPISODE, 0))
        assertThat(resolved.mediaItems.single().mediaId).isEqualTo("$ITEM|$EPISODE|0")
    }

    /**
     * A book download and one of its episodes are separate rows, so asking for the book
     * must not be answered by an episode's download. The two share an item id and differ
     * only in the episode key, which is the one place this can go wrong.
     */
    @Test
    fun `an episode download does not answer for the whole book`() = runTest {
        db.downloadDao().upsert(
            downloadRow(
                episodeKey = EPISODE,
                state = DownloadState.COMPLETED,
                tracksJson = """{"tracks":[""" + track(0, 0.0, 900.0, TRACK_0_URL, EPISODE) + "]}",
            ),
        )

        val result = resolver.resolve(account, ITEM, episodeId = null)

        assertThat(result.exceptionOrNull()).isInstanceOf(AuthExpiredException::class.java)
    }

    // endregion

    // region falling through to the streaming path

    @Test
    fun `an item with no download goes to the server`() = runTest {
        val result = resolver.resolve(account, ITEM, episodeId = null)

        assertThat(result.exceptionOrNull()).isInstanceOf(AuthExpiredException::class.java)
        assertThat(db.sessionLedgerDao().pendingUploads(account.serverId, account.userId)).isEmpty()
    }

    /**
     * A part downloaded book must stream rather than play the part that arrived. Half a
     * book that plays and then stops is worse than a book that needs the network, because
     * the listener cannot tell the difference between the end of the file and a fault.
     */
    @Test
    fun `a part downloaded book goes to the server`() = runTest {
        seedDownload(state = DownloadState.DOWNLOADING)

        val result = resolver.resolve(account, ITEM, episodeId = null)

        assertThat(result.exceptionOrNull()).isInstanceOf(AuthExpiredException::class.java)
        assertThat(db.sessionLedgerDao().pendingUploads(account.serverId, account.userId)).isEmpty()
    }

    /** Queued is the same case as part downloaded: nothing is on the phone yet. */
    @Test
    fun `a queued download goes to the server`() = runTest {
        seedDownload(state = DownloadState.QUEUED)

        assertThat(resolver.resolve(account, ITEM, episodeId = null).exceptionOrNull())
            .isInstanceOf(AuthExpiredException::class.java)
    }

    /**
     * A row marked for deletion has already lost its undo window in the listener's head.
     * Playing it would resurrect a book they asked to remove, so it streams instead.
     */
    @Test
    fun `a download marked for deletion goes to the server`() = runTest {
        seedDownload(state = DownloadState.PENDING_DELETE)

        assertThat(resolver.resolve(account, ITEM, episodeId = null).exceptionOrNull())
            .isInstanceOf(AuthExpiredException::class.java)
    }

    /** A manifest the app cannot read is not a playable download, and must not fail hard. */
    @Test
    fun `a download with an unreadable manifest goes to the server`() = runTest {
        db.downloadDao().upsert(
            downloadRow(episodeKey = "", state = DownloadState.COMPLETED, tracksJson = "not a manifest"),
        )

        assertThat(resolver.resolve(account, ITEM, episodeId = null).exceptionOrNull())
            .isInstanceOf(AuthExpiredException::class.java)
    }

    /** A manifest with no tracks is the same case, and reaches it by a different route. */
    @Test
    fun `a download with an empty manifest goes to the server`() = runTest {
        db.downloadDao().upsert(
            downloadRow(episodeKey = "", state = DownloadState.COMPLETED, tracksJson = """{"tracks":[]}"""),
        )

        assertThat(resolver.resolve(account, ITEM, episodeId = null).exceptionOrNull())
            .isInstanceOf(AuthExpiredException::class.java)
    }

    /**
     * A download that belongs to another account on the same phone is not this listener's
     * download. It streams, which is the right answer and also the private one.
     */
    @Test
    fun `another account's download does not resolve for this one`() = runTest {
        db.downloadDao().upsert(
            downloadRow(episodeKey = "", state = DownloadState.COMPLETED, userId = "user-2"),
        )

        assertThat(resolver.resolve(account, ITEM, episodeId = null).exceptionOrNull())
            .isInstanceOf(AuthExpiredException::class.java)
    }

    // endregion

    /**
     * What the resolver does when the bytes are gone: it resolves offline anyway.
     *
     * Nothing in this class ever writes a byte to the cache, so every offline test above
     * already runs against an empty one. This test says so on purpose, because the
     * behaviour is a decision and not an accident: the row and the manifest are the only
     * things consulted, and a cache miss then falls through to the network at the data
     * source instead. See the report for what that costs.
     */
    @Test
    fun `a completed row with no bytes on disk still resolves offline`() = runTest {
        seedDownload(state = DownloadState.COMPLETED)

        val resolved = resolver.resolve(account, ITEM, episodeId = null).getOrThrow()

        assertThat(resolved.isOffline).isTrue()
    }

    // region fixtures

    private suspend fun seedDownload(state: String) {
        db.downloadDao().upsert(downloadRow(episodeKey = "", state = state))
    }

    private fun downloadRow(
        episodeKey: String,
        state: String,
        userId: String = account.userId,
        tracksJson: String = MANIFEST_JSON,
    ) = DownloadEntity(
        serverId = account.serverId,
        userId = userId,
        libraryItemId = ITEM,
        episodeKey = episodeKey,
        title = TITLE,
        author = AUTHOR,
        mediaType = if (episodeKey.isEmpty()) "book" else "podcast",
        state = state,
        tracksJson = tracksJson,
        durationSec = 1_800.0,
        bytesTotal = 1_000,
        bytesDownloaded = if (state == DownloadState.COMPLETED) 1_000 else 400,
        percent = if (state == DownloadState.COMPLETED) 1.0f else 0.4f,
        requestedAtMs = 1,
        completedAtMs = if (state == DownloadState.COMPLETED) 2 else 0,
        error = null,
    )

    private fun progress(currentTimeSec: Double) = ProgressEntity(
        serverId = account.serverId,
        userId = account.userId,
        libraryItemId = ITEM,
        episodeKey = "",
        currentTimeSec = currentTimeSec,
        durationSec = 1_800.0,
        progress = currentTimeSec / 1_800.0,
        isFinished = false,
        lastUpdateMs = 10,
        startedAtMs = 1,
        serverLastUpdateMs = 10,
        isDirty = false,
    )

    private companion object {
        const val ITEM = "item-1"
        const val EPISODE = "episode-1"
        const val TITLE = "Fixture Book One"
        const val AUTHOR = "Fixture Author"

        /** Reserved by RFC 2606, so it cannot be a real address by construction. */
        const val RECORDED_HOST = "https://server.invalid"
        const val TRACK_0_URL = "$RECORDED_HOST/api/items/$ITEM/file/1"
        const val TRACK_1_URL = "$RECORDED_HOST/api/items/$ITEM/file/2"
        const val TRACK_2_URL = "$RECORDED_HOST/api/items/$ITEM/file/3"

        /**
         * One track of the manifest, written by hand exactly as the download wrote it.
         *
         * By hand because the playback module has no serialization dependency, and also
         * because the on-disk shape is part of what this pins: a rename of a field here
         * would silently stop every download from resolving.
         */
        fun track(index: Int, offsetSec: Double, durationSec: Double, url: String, episodeKey: String): String {
            val cacheKey = DownloadKeys.cacheKey(ITEM, episodeKey, index)
            return """{"index":$index, "startOffsetSec":$offsetSec, "durationSec":$durationSec, """ +
                """ "url":"$url", "mimeType":"audio/mpeg", "cacheKey":"$cacheKey", "sizeBytes":1000}"""
        }

        /**
         * Three tracks, listed out of offset order on purpose. The order in the file is the
         * order the server sent, and the resolver must sort by offset to get the book.
         */
        val MANIFEST_JSON = """{"tracks":[""" +
            track(2, 1_200.0, 600.0, TRACK_2_URL, "") + "," +
            track(0, 0.0, 600.0, TRACK_0_URL, "") + "," +
            track(1, 600.0, 600.0, TRACK_1_URL, "") +
            "]}"
    }

    // endregion
}
