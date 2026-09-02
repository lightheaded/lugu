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
import io.github.lightheaded.lugu.core.db.LuguDatabase
import io.github.lightheaded.lugu.core.model.AuthTokens
import io.github.lightheaded.lugu.core.db.NOTHING_PUSHED_SEC
import io.github.lightheaded.lugu.core.db.ProgressEntity
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.Clock
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pull-before-push over real Room, with the server's clock and the device's disagreeing.
 *
 * This is the wiring behind `ProgressConflictResolverTest`. The rule is pure and tested
 * on its own; what is tested here is that the repository hands it the right facts —
 * which is the half that was wrong. `ProgressEntity.serverLastUpdateMs` was filled from
 * whichever side last wrote the row, so a server timestamp and a
 * `System.currentTimeMillis()` ended up in one column and were compared as though they
 * came off one clock.
 *
 * The skew here is deliberately large and in the direction that hurts: the server's
 * clock reads an hour ahead of the device's. That is not an exotic setup. A CI emulator
 * and a container on the same host disagree by minutes routinely, and a phone that has
 * not reached a time server disagrees by more.
 *
 * `ProcessDeathResumptionTest` is what caught the defect in the first place, on a real
 * emulator, as "the book resumed 30056ms behind". It cannot say *why*, because it can
 * only see the position that came back. These tests can.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgressClockSkewTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val account = ActiveAccount(
        serverId = "srv",
        baseUrl = "https://server.invalid",
        userId = "usr",
        username = "listener",
        defaultLibraryId = null,
    )

    /** The device's clock. An hour behind the server's. */
    private val deviceNowMs = 1_700_000_000_000L

    /** The server's clock, as it stamps a row it accepts. */
    private val serverNowMs = deviceNowMs + 60 * 60 * 1_000L

    private val clock = object : Clock {
        override fun nowMs(): Long = deviceNowMs
    }

    private lateinit var db: LuguDatabase
    private lateinit var repository: ProgressRepository

    /** What the fake server currently holds for the item, or null for a 404. */
    private var serverHolds: MediaProgressDto? = null

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LuguDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.startsWith("/api/me/progress") -> {
                    val held = serverHolds
                    if (held == null) {
                        respond("", HttpStatusCode.NotFound)
                    } else {
                        respond(
                            content = AbsJson.encodeToString(MediaProgressDto.serializer(), held),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

                else -> respond("", HttpStatusCode.OK)
            }
        }

        val client = AbsClient(
            serverUrlProvider = StaticServerUrlProvider("https://server.invalid"),
            // A token that is healthy on the *device's* clock, which is the clock the
            // client checks the expiry against.
            tokenStore = InMemoryTokenStore(
                AuthTokens(
                    accessToken = "good",
                    refreshToken = "refresh",
                    accessTokenExpiresAtMs = deviceNowMs + 60 * 60 * 1_000L,
                ),
            ),
            deviceInfo = DeviceInfoDto(deviceId = "device-1"),
            nowMs = { deviceNowMs },
            http = HttpClient(engine) {
                expectSuccess = false
                install(ContentNegotiation) { json(AbsJson) }
            },
        )

        repository = ProgressRepository(
            client = client,
            progressDao = db.progressDao(),
            outboxDao = db.outboxDao(),
            positionHistoryDao = db.positionHistoryDao(),
            clock = clock,
        )
    }

    @After
    fun tearDown() = db.close()

    /**
     * The failure, reproduced: 49.9s locally, 19.8s on the server, and the server's copy
     * is the one this device put there.
     *
     * Before the fix the server won this, because its `lastUpdate` read an hour later
     * than the local row's — and the listener heard thirty seconds of the book again.
     */
    @Test
    fun `a stale server copy this device pushed does not move the position back`() = runTest {
        // The position the outbox managed to flush, and the server's stamp for it.
        givenPushed(positionSec = 19.829)
        serverHolds = serverCopy(currentTime = 19.829, lastUpdate = serverNowMs)

        // Listening carried on to 49.9s, and no flush got that far before the kill.
        repository.record(account, ITEM, null, positionSec = 49.885, durationSec = 90.0)

        val jump = repository.startSession(account, ITEM, null)

        assertThat(jump).isNull()
        assertThat(repository.get(account, ITEM, null)?.currentTimeSec).isEqualTo(49.885)
    }

    /** The same skew, and this time another device really did move the book on. */
    @Test
    fun `another device's position is still adopted`() = runTest {
        givenPushed(positionSec = 19.829)
        serverHolds = serverCopy(currentTime = 1_800.0, lastUpdate = serverNowMs)

        repository.record(account, ITEM, null, positionSec = 49.885, durationSec = 3_600.0)

        val jump = repository.startSession(account, ITEM, null)

        assertThat(jump).isNotNull()
        assertThat(jump?.toSec).isEqualTo(1_800.0)
        assertThat(repository.get(account, ITEM, null)?.currentTimeSec).isEqualTo(1_800.0)
    }

    /**
     * Adopting a server copy records the server's revision as a revision.
     *
     * The listening time and the revision are the same number on a server copy, and the
     * two columns still both have to hold it. The revision column is what the *next*
     * conflict is decided against, so a row adopted and then listened past has to know
     * which server copy it already accounted for.
     */
    @Test
    fun `adopting a server copy records the server's own revision`() = runTest {
        serverHolds = serverCopy(currentTime = 1_800.0, lastUpdate = serverNowMs)

        repository.startSession(account, ITEM, null)

        val row = db.progressDao().get(account.serverId, account.userId, ITEM, "")
        assertThat(row?.serverLastUpdateMs).isEqualTo(serverNowMs)
        // The listening time is when the listening happened, which for another device's
        // copy is the server's own stamp. Stamping it with this device's clock instead
        // would flatten the Continue shelf on every login sweep.
        assertThat(row?.lastUpdateMs).isEqualTo(serverNowMs)
    }

    /**
     * A row adopted from the server and then listened past keeps the local position.
     *
     * This is the case the revision column exists for. Before it, adopting wrote the
     * server's clock into the row and the next conflict compared that against a local
     * `System.currentTimeMillis()` — so on a device running behind the server, the same
     * copy it had just adopted won again and the listening since was thrown away.
     */
    @Test
    fun `listening past an adopted position is not undone by the copy it came from`() = runTest {
        serverHolds = serverCopy(currentTime = 1_800.0, lastUpdate = serverNowMs)
        repository.startSession(account, ITEM, null)

        repository.record(account, ITEM, null, positionSec = 2_400.0, durationSec = 3_600.0)
        val jump = repository.startSession(account, ITEM, null)

        assertThat(jump).isNull()
        assertThat(repository.get(account, ITEM, null)?.currentTimeSec).isEqualTo(2_400.0)
    }

    /**
     * A pull that found nothing to resolve still records the stamp it read.
     *
     * Without it, the next disagreement on a row this device pushed to but never read
     * back has nothing to compare the server's clock against, and the rule falls back to
     * the one case where it gives ground.
     */
    @Test
    fun `a pull with nothing to resolve still records the server stamp`() = runTest {
        givenPushed(positionSec = 100.0)
        serverHolds = serverCopy(currentTime = 100.0, lastUpdate = serverNowMs)

        repository.startSession(account, ITEM, null)

        val row = db.progressDao().get(account.serverId, account.userId, ITEM, "")
        assertThat(row?.serverLastUpdateMs).isEqualTo(serverNowMs)
    }

    /**
     * Accepting a push records the position the server accepted, never a timestamp.
     *
     * The PATCH answers with an empty body, so the stamp the server gave the row is
     * genuinely unknown at that moment. Writing this device's clock into the column that
     * holds the server's instead is the whole of the defect, so the query is asserted to
     * leave that column alone.
     */
    @Test
    fun `accepting a push records what the server took and not when`() = runTest {
        givenPushed(positionSec = 19.829)
        repository.record(account, ITEM, null, positionSec = 42.0, durationSec = 90.0)
        assertThat(db.progressDao().get(account.serverId, account.userId, ITEM, "")?.isDirty).isTrue()

        db.progressDao().markClean(
            serverId = account.serverId,
            userId = account.userId,
            itemId = ITEM,
            episodeKey = "",
            pushedTimeSec = 42.0,
            pushedFinished = false,
        )

        val row = db.progressDao().get(account.serverId, account.userId, ITEM, "")
        assertThat(row?.pushedTimeSec).isEqualTo(42.0)
        assertThat(row?.isDirty).isFalse()
        assertThat(row?.serverLastUpdateMs).isEqualTo(0L)
        // The position itself is untouched by being marked clean.
        assertThat(row?.currentTimeSec).isEqualTo(42.0)
    }

    /** A row lugu has never pushed says so, rather than claiming the server took zero. */
    @Test
    fun `a row with no accepted push is marked as having none`() = runTest {
        repository.record(account, ITEM, null, positionSec = 5.0, durationSec = 90.0)

        val row = db.progressDao().get(account.serverId, account.userId, ITEM, "")
        assertThat(row?.pushedTimeSec).isEqualTo(NOTHING_PUSHED_SEC)
    }

    /**
     * The offline promise: a week of listening with nothing to push to is not thrown
     * away when the connection comes back and the server still holds where you were.
     */
    @Test
    fun `offline listening is not lost to the server's own echo`() = runTest {
        givenPushed(positionSec = 60.0)
        serverHolds = serverCopy(currentTime = 60.0, lastUpdate = serverNowMs)

        // Four hours of a long book, all of it offline.
        repository.record(account, ITEM, null, positionSec = 14_400.0, durationSec = 40_000.0)

        repository.startSession(account, ITEM, null)

        assertThat(repository.get(account, ITEM, null)?.currentTimeSec).isEqualTo(14_400.0)
    }

    // -----------------------------------------------------------------------------------

    /** A row the server has already accepted one position from. */
    private suspend fun givenPushed(positionSec: Double) {
        db.progressDao().upsert(
            ProgressEntity(
                serverId = account.serverId,
                userId = account.userId,
                libraryItemId = ITEM,
                episodeKey = "",
                currentTimeSec = positionSec,
                durationSec = 90.0,
                progress = positionSec / 90.0,
                isFinished = false,
                lastUpdateMs = deviceNowMs - 1_000,
                startedAtMs = deviceNowMs - 10_000,
                serverLastUpdateMs = 0L,
                pushedTimeSec = positionSec,
                pushedFinished = false,
                isDirty = false,
            ),
        )
    }

    private fun serverCopy(currentTime: Double, lastUpdate: Long) = MediaProgressDto(
        libraryItemId = ITEM,
        duration = 90.0,
        progress = currentTime / 90.0,
        currentTime = currentTime,
        isFinished = false,
        lastUpdate = lastUpdate,
        startedAt = lastUpdate - 60_000,
    )

    private companion object {
        const val ITEM = "li_skew"
    }
}
