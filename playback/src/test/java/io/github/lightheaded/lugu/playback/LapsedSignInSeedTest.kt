package io.github.lightheaded.lugu.playback

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.api.AbsJson
import io.github.lightheaded.lugu.core.api.DeviceInfoDto
import io.github.lightheaded.lugu.core.api.InMemoryTokenStore
import io.github.lightheaded.lugu.core.api.MediaProgressDto
import io.github.lightheaded.lugu.core.api.StaticServerUrlProvider
import io.github.lightheaded.lugu.core.api.toDomain
import io.github.lightheaded.lugu.core.db.LuguDatabase
import io.github.lightheaded.lugu.core.db.ServerCopyWrite
import io.github.lightheaded.lugu.core.db.episodeKeyOf
import io.github.lightheaded.lugu.core.model.AuthTokens
import io.github.lightheaded.lugu.core.model.ProgressResolution
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.Clock
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A position listened to while the sign-in had lapsed, met at the next sign-in by the
 * server's copy, over real Room.
 *
 * While the account is lapsed, a downloaded book plays and its positions wait in the
 * outbox. The sign-in seeds Room from the server and then flushes the outbox. Newer
 * listening wins (maintainer, 25 November 2026). When another device moved the book after
 * the phone's listening, the seed adopts that position, and the push queued during the
 * lapse must go with the row it described: sent after the seed, it put the old position
 * back on the server while Room showed the new one. When the other device wrote before the
 * phone's listening, the phone's position stays and goes out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LapsedSignInSeedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val account = ActiveAccount(
        serverId = "srv",
        baseUrl = "https://server.invalid",
        userId = "usr",
        username = "listener",
        defaultLibraryId = null,
    )

    private var nowMs = 1_700_000_000_000L
    private val clock = object : Clock {
        override fun nowMs(): Long = nowMs
    }

    private lateinit var db: LuguDatabase
    private lateinit var repository: ProgressRepository

    /** The bodies of the progress pushes the fake server received, in order. */
    private val pushed = mutableListOf<String>()

    /** Runs while the fake server holds a PATCH, as listening does while a push is in flight. */
    private var duringPush: (suspend () -> Unit)? = null

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LuguDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Patch) {
                pushed += String(request.body.toByteArray())
                duringPush?.also { duringPush = null }?.invoke()
            }
            respond("", HttpStatusCode.OK)
        }

        repository = ProgressRepository(
            client = AbsClient(
                serverUrlProvider = StaticServerUrlProvider("https://server.invalid"),
                // The sign-in has just succeeded, so the flush after it holds a token.
                tokenStore = InMemoryTokenStore(
                    AuthTokens(accessToken = "good", refreshToken = "refresh", accessTokenExpiresAtMs = nowMs * 2),
                ),
                deviceInfo = DeviceInfoDto(deviceId = "device-1"),
                nowMs = { nowMs },
                http = HttpClient(engine) {
                    expectSuccess = false
                    install(ContentNegotiation) { json(AbsJson) }
                },
            ),
            progressDao = db.progressDao(),
            outboxDao = db.outboxDao(),
            positionHistoryDao = db.positionHistoryDao(),
            clock = clock,
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a newer position from another device is not overwritten by one from the lapse`() = runTest {
        lapsedListeningThenSignIn(episodeId = null)
    }

    /** The same for a podcast episode, whose push is keyed by item and episode. */
    @Test
    fun `a newer episode position from another device is not overwritten by one from the lapse`() = runTest {
        lapsedListeningThenSignIn(episodeId = EPISODE)
    }

    /** Nothing else wrote the server's copy, so the lapse's listening is the newest and goes out. */
    @Test
    fun `a position from the lapse that is newer than the server's goes out after the sign-in`() = runTest {
        repository.seedFromServer(account, listOf(serverCopy(currentTime = 300.0, lastUpdate = STAMP_BEFORE)))
        nowMs += 60_000L
        repository.record(account, ITEM, null, positionSec = 600.0, durationSec = DURATION)

        // The sign-in answers with the copy this device already read.
        repository.seedFromServer(account, listOf(serverCopy(currentTime = 300.0, lastUpdate = STAMP_BEFORE)))
        flushAll()

        assertThat(pushed).hasSize(1)
        assertThat(pushed.single()).contains("\"currentTime\":600.0")
        assertThat(repository.get(account, ITEM, null)?.currentTimeSec).isEqualTo(600.0)
        assertThat(db.outboxDao().peek(account.serverId, account.userId)).isEmpty()
    }

    /**
     * The seed decides on a row it read before it writes. Listening that lands in between is
     * newer than both, so the write leaves that row and its push alone.
     */
    @Test
    fun `a row that moved after the seed read it is left alone`() = runTest {
        repository.record(account, ITEM, null, positionSec = 600.0, durationSec = DURATION)
        val judged = requireNotNull(db.progressDao().get(account.serverId, account.userId, ITEM, episodeKeyOf(null)))
        nowMs += 5_000L
        repository.record(account, ITEM, null, positionSec = 605.0, durationSec = DURATION)

        val written = db.progressDao().writeServerCopies(
            listOf(
                ServerCopyWrite(
                    row = judged.copy(currentTimeSec = 1_800.0, isDirty = false),
                    replacedLastUpdateMs = judged.lastUpdateMs,
                    queuedPushKey = "$ITEM#${episodeKeyOf(null)}",
                ),
            ),
        )

        assertThat(written).isEmpty()
        assertThat(repository.get(account, ITEM, null)?.currentTimeSec).isEqualTo(605.0)
        assertThat(db.outboxDao().peek(account.serverId, account.userId)).hasSize(1)
    }

    /**
     * A tick lands while the push before it is in flight. The PATCH answers for the old
     * position, so the row must stay dirty: its own push still waits in the outbox.
     */
    @Test
    fun `a push accepted for an older position leaves a newer row dirty`() = runTest {
        repository.record(account, ITEM, null, positionSec = 600.0, durationSec = DURATION)
        duringPush = {
            nowMs += 5_000L
            repository.record(account, ITEM, null, positionSec = 605.0, durationSec = DURATION)
        }

        val inFlight = db.outboxDao().peek(account.serverId, account.userId).single()
        repository.flushEntry(inFlight)

        val row = requireNotNull(db.progressDao().get(account.serverId, account.userId, ITEM, episodeKeyOf(null)))
        assertThat(row.currentTimeSec).isEqualTo(605.0)
        assertThat(row.isDirty).isTrue()
        // What the server took is still recorded, because it did take it.
        assertThat(row.pushedTimeSec).isEqualTo(600.0)
    }

    /**
     * The same tick, then a sweep that brings back the server's copy of the push in
     * flight. The newer position and its push both survive, and the push goes out.
     */
    @Test
    fun `a sweep after a push in flight keeps the position recorded during it`() = runTest {
        repository.seedFromServer(account, listOf(serverCopy(currentTime = 300.0, lastUpdate = STAMP_BEFORE)))
        nowMs += 60_000L
        repository.record(account, ITEM, null, positionSec = 600.0, durationSec = DURATION)
        duringPush = {
            nowMs += 5_000L
            repository.record(account, ITEM, null, positionSec = 605.0, durationSec = DURATION)
        }
        val inFlight = db.outboxDao().peek(account.serverId, account.userId).single()
        if (repository.flushEntry(inFlight)) db.outboxDao().delete(inFlight.rowId)

        // ReconcileWorker, catchUp or a sign-in: the server holds the push it accepted.
        repository.seedFromServer(account, listOf(serverCopy(currentTime = 600.0, lastUpdate = STAMP_ELSEWHERE)))

        assertThat(repository.get(account, ITEM, null)?.currentTimeSec).isEqualTo(605.0)
        assertThat(db.outboxDao().peek(account.serverId, account.userId)).hasSize(1)
        flushAll()
        assertThat(pushed.last()).contains("\"currentTime\":605.0")
    }

    /**
     * A row that reads as clean can still have a push waiting: one recorded after the row
     * was read, or a row that an older push marked clean before markClean checked. The
     * sweep judged no push for it, so it writes nothing and drops nothing.
     */
    @Test
    fun `a clean row with a push waiting keeps the row and the push`() = runTest {
        repository.record(account, ITEM, null, positionSec = 605.0, durationSec = DURATION)
        val recorded = requireNotNull(db.progressDao().get(account.serverId, account.userId, ITEM, episodeKeyOf(null)))
        // The row as the old unconditional markClean left it: clean, its push still queued.
        db.progressDao().upsert(recorded.copy(isDirty = false))

        val written = db.progressDao().writeServerCopies(
            listOf(
                ServerCopyWrite(
                    row = recorded.copy(currentTimeSec = 600.0, isDirty = false),
                    replacedLastUpdateMs = null,
                    queuedPushKey = "$ITEM#${episodeKeyOf(null)}",
                ),
            ),
        )

        assertThat(written).isEmpty()
        assertThat(repository.get(account, ITEM, null)?.currentTimeSec).isEqualTo(605.0)
        assertThat(db.outboxDao().peek(account.serverId, account.userId)).hasSize(1)
    }

    /** A clean row with nothing queued takes the server's copy, as it always did. */
    @Test
    fun `a clean row with nothing queued takes the server's copy`() = runTest {
        repository.seedFromServer(account, listOf(serverCopy(currentTime = 300.0, lastUpdate = STAMP_BEFORE)))
        repository.seedFromServer(account, listOf(serverCopy(currentTime = 900.0, lastUpdate = STAMP_ELSEWHERE)))

        assertThat(repository.get(account, ITEM, null)?.currentTimeSec).isEqualTo(900.0)
    }

    /**
     * The review's scenario. The phone last read the server at 300 s, plays to 600 s while
     * lapsed, another device then moves the server to 1800 s, and then the sign-in seeds
     * and flushes.
     */
    private suspend fun lapsedListeningThenSignIn(episodeId: String?) {
        repository.seedFromServer(account, listOf(serverCopy(300.0, STAMP_BEFORE, episodeId)))
        nowMs += 60_000L
        repository.record(account, ITEM, episodeId, positionSec = 600.0, durationSec = DURATION)
        assertThat(db.outboxDao().peek(account.serverId, account.userId)).hasSize(1)

        repository.seedFromServer(account, listOf(serverCopy(1_800.0, STAMP_AFTER_LAPSE, episodeId)))
        flushAll()

        assertThat(pushed).isEmpty()
        assertThat(db.outboxDao().peek(account.serverId, account.userId)).isEmpty()
        val row = requireNotNull(db.progressDao().get(account.serverId, account.userId, ITEM, episodeKeyOf(episodeId)))
        assertThat(row.currentTimeSec).isEqualTo(1_800.0)
        assertThat(row.isDirty).isFalse()
        // The listening from the lapse is not lost without a trace: the history holds the
        // jump, under the reason the history screen reads as "From another device".
        val jump = requireNotNull(repository.lastJump(account, ITEM))
        assertThat(jump.fromSec).isEqualTo(600.0)
        assertThat(jump.toSec).isEqualTo(1_800.0)
        assertThat(jump.reason).isEqualTo(SeekReasons.OTHER_DEVICE)
        assertThat(jump.episodeKey).isEqualTo(episodeKeyOf(episodeId))
    }

    /**
     * The maintainer's question of 25 November 2026: the phone listens offline for days
     * while the session is ended, and another device wrote an older position before that.
     * "Newer listening wins": the phone's position stays in Room and goes to the server.
     */
    @Test
    fun `a position from the lapse that is newer than another device's goes out after the sign-in`() = runTest {
        lapsedListeningOutlivesAnOlderWrite(episodeId = null)
    }

    /** The same for a podcast episode, whose push is keyed by item and episode. */
    @Test
    fun `an episode position from the lapse that is newer than another device's goes out`() = runTest {
        lapsedListeningOutlivesAnOlderWrite(episodeId = EPISODE)
    }

    /** A finished mark made offline is the newest listening, and survives the seed. */
    @Test
    fun `a finished mark made in the lapse survives an older position from another device`() = runTest {
        repository.seedFromServer(account, listOf(serverCopy(currentTime = 300.0, lastUpdate = STAMP_BEFORE)))
        nowMs += 60_000L
        repository.setFinished(account, ITEM, null, isFinished = true)

        repository.seedFromServer(account, listOf(serverCopy(currentTime = 1_800.0, lastUpdate = STAMP_ELSEWHERE)))
        flushAll()

        assertThat(pushed).hasSize(1)
        assertThat(pushed.single()).contains("\"isFinished\":true")
        val row = requireNotNull(db.progressDao().get(account.serverId, account.userId, ITEM, episodeKeyOf(null)))
        assertThat(row.isFinished).isTrue()
        assertThat(row.currentTimeSec).isEqualTo(DURATION)
        assertThat(repository.lastJump(account, ITEM)).isNull()
    }

    /**
     * The seed kept the phone's newer position, and the listener presses play before the
     * push went out. The check at the session start meets the same older copy from
     * another device. It must not adopt it and drop the push: the two rules agree on the
     * copy that the seed judged.
     */
    @Test
    fun `a play before the push went out does not adopt the older copy the seed judged`() = runTest {
        repository.seedFromServer(account, listOf(serverCopy(currentTime = 300.0, lastUpdate = STAMP_BEFORE)))
        nowMs += 60_000L
        repository.record(account, ITEM, null, positionSec = 600.0, durationSec = DURATION)
        val older = serverCopy(currentTime = 1_800.0, lastUpdate = STAMP_ELSEWHERE)
        repository.seedFromServer(account, listOf(older))

        // What checkPositionLate and startSession do with the server's copy in hand.
        val server = older.toDomain()
        val resolution = repository.resolveAgainst(account, ITEM, null, server)
        val jump = repository.settle(account, ITEM, null, server, resolution)

        assertThat(resolution).isNotInstanceOf(ProgressResolution.AdoptServer::class.java)
        assertThat(jump).isNull()
        assertThat(repository.get(account, ITEM, null)?.currentTimeSec).isEqualTo(600.0)
        flushAll()
        assertThat(pushed).hasSize(1)
        assertThat(pushed.single()).contains("\"currentTime\":600.0")
    }

    /**
     * A copy that another device wrote after the seed is new evidence. The stamp the seed
     * recorded is for the older copy only, so the check at a session start still adopts
     * this one.
     */
    @Test
    fun `a copy written after the seed is still adopted at the next session start`() = runTest {
        repository.seedFromServer(account, listOf(serverCopy(currentTime = 300.0, lastUpdate = STAMP_BEFORE)))
        nowMs += 60_000L
        repository.record(account, ITEM, null, positionSec = 600.0, durationSec = DURATION)
        repository.seedFromServer(account, listOf(serverCopy(currentTime = 1_800.0, lastUpdate = STAMP_ELSEWHERE)))
        assertThat(repository.get(account, ITEM, null)?.currentTimeSec).isEqualTo(600.0)

        val newer = serverCopy(currentTime = 2_400.0, lastUpdate = STAMP_AFTER_LAPSE).toDomain()
        val resolution = repository.resolveAgainst(account, ITEM, null, newer)

        assertThat(resolution).isInstanceOf(ProgressResolution.AdoptServer::class.java)
    }

    /**
     * The phone last read the server at 300 s. Another device writes 1800 s, and only
     * days later the phone plays to 600 s while lapsed. Then the sign-in seeds and flushes.
     */
    private suspend fun lapsedListeningOutlivesAnOlderWrite(episodeId: String?) {
        repository.seedFromServer(account, listOf(serverCopy(300.0, STAMP_BEFORE, episodeId)))
        nowMs += 60_000L
        repository.record(account, ITEM, episodeId, positionSec = 600.0, durationSec = DURATION)

        repository.seedFromServer(account, listOf(serverCopy(1_800.0, STAMP_ELSEWHERE, episodeId)))

        val row = requireNotNull(db.progressDao().get(account.serverId, account.userId, ITEM, episodeKeyOf(episodeId)))
        assertThat(row.currentTimeSec).isEqualTo(600.0)
        assertThat(row.isDirty).isTrue()
        assertThat(db.outboxDao().peek(account.serverId, account.userId)).hasSize(1)
        // Nothing on the phone moved, so there is no "From another device" to show.
        assertThat(repository.lastJump(account, ITEM)).isNull()

        flushAll()
        assertThat(pushed).hasSize(1)
        assertThat(pushed.single()).contains("\"currentTime\":600.0")
        assertThat(db.outboxDao().peek(account.serverId, account.userId)).isEmpty()
    }

    /** What `OutboxFlusher` does after the sign-in, one entry at a time. */
    private suspend fun flushAll() {
        db.outboxDao().peek(account.serverId, account.userId).forEach { entry ->
            if (repository.flushEntry(entry)) db.outboxDao().delete(entry.rowId)
        }
    }

    private fun serverCopy(currentTime: Double, lastUpdate: Long, episodeId: String? = null) = MediaProgressDto(
        libraryItemId = ITEM,
        episodeId = episodeId,
        duration = DURATION,
        progress = currentTime / DURATION,
        currentTime = currentTime,
        isFinished = false,
        lastUpdate = lastUpdate,
        startedAt = STAMP_BEFORE - 1_000_000L,
    )

    private companion object {
        const val ITEM = "li_book"
        const val EPISODE = "ep_one"
        const val DURATION = 3_600.0
        const val STAMP_BEFORE = 1_699_000_000_000L

        /** Another device's write, days before the phone's listening in the lapse. */
        const val STAMP_ELSEWHERE = 1_699_500_000_000L

        /** Another device's write, an hour after the phone's listening in the lapse. */
        const val STAMP_AFTER_LAPSE = 1_700_003_600_000L
    }
}
