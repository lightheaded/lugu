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
import io.github.lightheaded.lugu.core.model.ProgressResolution
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.Clock
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The check a downloaded item runs after it has started, over real Room.
 *
 * A downloaded book plays from Room at once and asks the server afterwards. For the time
 * between, the phone can hold a position older than one from another device. These tests
 * pin what keeps that position safe: the push of the item waits for the check, and an
 * adopted server copy takes the stale push with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LatePositionCheckTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val account = ActiveAccount(
        serverId = "srv",
        baseUrl = "https://server.invalid",
        userId = "usr",
        username = "listener",
        defaultLibraryId = null,
    )

    private val nowMs = 1_700_000_000_000L
    private val clock = object : Clock {
        override fun nowMs(): Long = nowMs
    }

    private lateinit var db: LuguDatabase
    private lateinit var repository: ProgressRepository

    /** What the fake server holds for the item, or null for a 404. */
    private var serverHolds: MediaProgressDto? = null

    /** The progress pushes the fake server received, in order. */
    private val pushed = mutableListOf<String>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LuguDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Patch -> {
                    pushed += request.url.encodedPath
                    respond("", HttpStatusCode.OK)
                }

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

        repository = ProgressRepository(
            client = AbsClient(
                serverUrlProvider = StaticServerUrlProvider("https://server.invalid"),
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

    /**
     * The failure this guards against: a pause in the first second of a downloaded book
     * sends the phone's old position before the server was asked about a newer one.
     */
    @Test
    fun `a held item is not pushed until its check is done`() = runTest {
        repository.record(account, ITEM, null, positionSec = 100.0, durationSec = 3_600.0)
        val hold = repository.holdPushes(account, ITEM, null)

        val entry = db.outboxDao().peek(account.serverId, account.userId).single()
        val flush = async { repository.flushEntry(entry) }
        runCurrent()

        assertThat(flush.isCompleted).isFalse()
        assertThat(pushed).isEmpty()

        repository.releasePushes(hold)

        assertThat(flush.await()).isTrue()
        assertThat(pushed).hasSize(1)
    }

    /** Another item's push does not wait for this one's check. */
    @Test
    fun `a hold covers only its own item`() = runTest {
        repository.record(account, OTHER_ITEM, null, positionSec = 50.0, durationSec = 3_600.0)
        repository.holdPushes(account, ITEM, null)

        val entry = db.outboxDao().peek(account.serverId, account.userId).single()

        assertThat(repository.flushEntry(entry)).isTrue()
        assertThat(pushed).hasSize(1)
    }

    /**
     * The check finds another device ahead. The phone's queued position is the one the
     * server's copy replaced, so it must never reach the server.
     */
    @Test
    fun `an adopted server copy drops the push that was waiting`() = runTest {
        repository.record(account, ITEM, null, positionSec = 100.0, durationSec = 3_600.0)
        val hold = repository.holdPushes(account, ITEM, null)
        val entry = db.outboxDao().peek(account.serverId, account.userId).single()
        // On a real dispatcher, so the hold's timeout runs on real time. The steps below
        // wait on Room and the fake server, and runTest skips virtual time while they do.
        val flush = async(Dispatchers.Default) { repository.flushEntry(entry) }

        serverHolds = serverCopy(currentTime = 1_800.0)
        val server = repository.fetchServerCopy(ITEM, null)
        val resolution = repository.resolveAgainst(account, ITEM, null, server)
        assertThat(resolution).isInstanceOf(ProgressResolution.AdoptServer::class.java)
        repository.settle(account, ITEM, null, server, resolution)
        repository.releasePushes(hold)

        assertThat(flush.await()).isTrue()
        assertThat(pushed).isEmpty()
        assertThat(db.outboxDao().peek(account.serverId, account.userId)).isEmpty()
        assertThat(repository.get(account, ITEM, null)?.currentTimeSec).isEqualTo(1_800.0)
    }

    /** The server agrees with the phone, so the phone's position goes out as usual. */
    @Test
    fun `a check that keeps the local position lets it go out`() = runTest {
        repository.record(account, ITEM, null, positionSec = 100.0, durationSec = 3_600.0)
        val hold = repository.holdPushes(account, ITEM, null)

        serverHolds = null
        val server = repository.fetchServerCopy(ITEM, null)
        repository.settle(account, ITEM, null, server, repository.resolveAgainst(account, ITEM, null, server))
        repository.releasePushes(hold)

        val entry = db.outboxDao().peek(account.serverId, account.userId).single()
        assertThat(repository.flushEntry(entry)).isTrue()
        assertThat(pushed).hasSize(1)
    }

    /** A second start of the same book replaces the hold, and the first release frees nothing. */
    @Test
    fun `a newer hold on the same item outlives the older one`() = runTest {
        repository.record(account, ITEM, null, positionSec = 100.0, durationSec = 3_600.0)
        val first = repository.holdPushes(account, ITEM, null)
        val second = repository.holdPushes(account, ITEM, null)
        repository.releasePushes(first)

        val entry = db.outboxDao().peek(account.serverId, account.userId).single()
        val flush = async { repository.flushEntry(entry) }
        runCurrent()
        assertThat(flush.isCompleted).isFalse()

        repository.releasePushes(second)
        assertThat(flush.await()).isTrue()
        assertThat(pushed).hasSize(1)
    }

    /** The sweep before a play press leaves the item in the player alone. */
    @Test
    fun `the catch-up sweep skips the item in the player`() = runTest {
        repository.record(account, ITEM, null, positionSec = 100.0, durationSec = 3_600.0)
        val remote = listOf(serverCopy(currentTime = 1_800.0))

        repository.seedFromServer(
            account,
            remote,
            except = io.github.lightheaded.lugu.core.model.ProgressKey(ITEM, null),
        )
        assertThat(repository.get(account, ITEM, null)?.currentTimeSec).isEqualTo(100.0)

        repository.seedFromServer(account, remote)
        assertThat(repository.get(account, ITEM, null)?.currentTimeSec).isEqualTo(1_800.0)
    }

    private fun serverCopy(currentTime: Double) = MediaProgressDto(
        libraryItemId = ITEM,
        duration = 3_600.0,
        progress = currentTime / 3_600.0,
        currentTime = currentTime,
        isFinished = false,
        lastUpdate = nowMs + 5_000L,
        startedAt = nowMs - 1_000_000L,
    )

    private companion object {
        const val ITEM = "li_book"
        const val OTHER_ITEM = "li_other"
    }
}
