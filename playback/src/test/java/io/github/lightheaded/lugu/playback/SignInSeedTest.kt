package io.github.lightheaded.lugu.playback

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.api.AbsJson
import io.github.lightheaded.lugu.core.api.ConnectionRace
import io.github.lightheaded.lugu.core.api.DeviceInfoDto
import io.github.lightheaded.lugu.core.api.InMemoryTokenStore
import io.github.lightheaded.lugu.core.api.StaticServerUrlProvider
import io.github.lightheaded.lugu.core.db.LuguDatabase
import io.github.lightheaded.lugu.core.db.ProgressDao
import io.github.lightheaded.lugu.core.db.ProgressEntity
import io.github.lightheaded.lugu.core.db.ServerEntity
import io.github.lightheaded.lugu.core.model.AuthTokens
import io.github.lightheaded.lugu.core.sync.ActiveServerUrlProvider
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.Clock
import io.github.lightheaded.lugu.core.sync.ConnectionPrefs
import io.github.lightheaded.lugu.core.sync.FlushResult
import io.github.lightheaded.lugu.core.sync.InMemoryAccountTokens
import io.github.lightheaded.lugu.core.sync.OutboxFlusher
import io.github.lightheaded.lugu.core.sync.PlayingItem
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import io.github.lightheaded.lugu.core.sync.SessionLedgerRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
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
 * What a sign-in does with the positions it brings, over real Room and the real
 * [AuthRepository], against a fake server.
 *
 * Three rules. The item in the player is left out of the seed, as every sweep leaves it out.
 * A seed that fails does not stop the sign-in. And a flush that meets a sign-in which changed
 * under its refresh tries again, because that is a race and not a lapse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignInSeedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private var nowMs = 1_700_000_000_000L
    private val clock = object : Clock {
        override fun nowMs(): Long = nowMs
    }

    private lateinit var db: LuguDatabase
    private lateinit var tokens: InMemoryAccountTokens
    private val playing = PlayingItem()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, LuguDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tokens = InMemoryAccountTokens(activeServerId = SERVER_ID)
    }

    @After
    fun tearDown() = db.close()

    /**
     * A lapsed download plays, and the listener signs in while it does. The server's copy
     * of that book is newer and from another device, but the book is in the player: its
     * position moves only through a session start, which can show the jump. Written here,
     * the next tick put the old position back over it.
     */
    @Test
    fun `a sign-in leaves the playing item alone`() = runTest {
        lapsedAccountRow()
        val progress = progressRepository(db.progressDao())
        val account = requireNotNull(authRepository(progress).lapsedAccount())
        progress.record(account, ITEM, null, positionSec = 600.0, durationSec = DURATION)
        playing.set(ITEM, null)

        val signedIn = authRepository(progress, http = fakeServer(serverPosition = 1_800.0)).login(BASE_URL, USERNAME, "pw")

        assertThat(signedIn.isSuccess).isTrue()
        assertThat(progress.get(account, ITEM, null)?.currentTimeSec).isEqualTo(600.0)
        assertThat(db.outboxDao().peek(account.serverId, account.userId)).hasSize(1)
        // No "From another device" for a position the player still holds.
        assertThat(progress.lastJump(account, ITEM)).isNull()
    }

    /** With nothing in the player, the same sign-in takes the server's copy. */
    @Test
    fun `a sign-in with nothing playing takes the newer copy from another device`() = runTest {
        lapsedAccountRow()
        val progress = progressRepository(db.progressDao())
        val account = requireNotNull(authRepository(progress).lapsedAccount())
        progress.record(account, ITEM, null, positionSec = 600.0, durationSec = DURATION)

        authRepository(progress, http = fakeServer(serverPosition = 1_800.0)).login(BASE_URL, USERNAME, "pw")

        assertThat(progress.get(account, ITEM, null)?.currentTimeSec).isEqualTo(1_800.0)
        assertThat(db.outboxDao().peek(account.serverId, account.userId)).isEmpty()
    }

    /**
     * The server took the password. A seed that throws on the way must not turn that into a
     * failed sign-in: the sign-in screen has to get the listener in.
     */
    @Test
    fun `a seed that fails does not stop the sign-in`() = runTest {
        val broken = object : ProgressDao by db.progressDao() {
            override suspend fun dirty(serverId: String, userId: String): List<ProgressEntity> =
                throw IllegalStateException("the database refused the read")
        }
        val progress = progressRepository(broken)
        val auth = authRepository(progress, http = fakeServer(serverPosition = 1_800.0))

        val signedIn = auth.login(BASE_URL, USERNAME, "pw")

        assertThat(signedIn.isSuccess).isTrue()
        assertThat(auth.account()?.serverId).isEqualTo(SERVER_ID)
        assertThat(tokens.tokensFor(SERVER_ID)?.accessToken).isEqualTo("access")
        assertThat(auth.lapsedAccount()).isNull()
    }

    /**
     * A refresh whose tokens changed under it is a race. The entry is not counted as an
     * attempt, and the flush asks WorkManager for a retry instead of waiting for a sign-in
     * that nobody needs to make.
     */
    @Test
    fun `a flush that meets a sign-in which changed during the refresh is retried`() = runTest {
        lapsedAccountRow()
        tokens.saveFor(SERVER_ID, AuthTokens("signed-in", "refresh", accessTokenExpiresAtMs = Long.MAX_VALUE))
        val store = InMemoryTokenStore(AuthTokens("old", "refresh-1", accessTokenExpiresAtMs = 0L))
        val racingClient = AbsClient(
            serverUrlProvider = StaticServerUrlProvider(BASE_URL),
            tokenStore = store,
            deviceInfo = DeviceInfoDto(deviceId = "sign-in-seed"),
            nowMs = { nowMs },
            http = HttpClient(
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/auth/refresh")) {
                        // A sign-in lands while the refresh is in flight.
                        store.save(AuthTokens("newer", "refresh-2", accessTokenExpiresAtMs = Long.MAX_VALUE))
                        respond(
                            """{"user":{"id":"u1","username":"$USERNAME","accessToken":"new","refreshToken":"r"}}""",
                            HttpStatusCode.OK,
                            JSON,
                        )
                    } else {
                        error("Nothing else is expected: the push stops at the refresh")
                    }
                },
            ) {
                expectSuccess = false
                install(ContentNegotiation) { json(AbsJson) }
            },
        )
        val progress = ProgressRepository(
            client = racingClient,
            progressDao = db.progressDao(),
            outboxDao = db.outboxDao(),
            positionHistoryDao = db.positionHistoryDao(),
            clock = clock,
        )
        val auth = authRepository(progress)
        val account = requireNotNull(auth.account())
        progress.record(account, ITEM, null, positionSec = 600.0, durationSec = DURATION)
        val flusher = OutboxFlusher(
            outboxDao = db.outboxDao(),
            progressRepository = progress,
            sessionLedgerRepository = SessionLedgerRepository(
                client = racingClient,
                dao = db.sessionLedgerDao(),
                deviceInfo = DeviceInfoDto(deviceId = "sign-in-seed"),
                clock = clock,
            ),
            authRepository = auth,
            clock = clock,
        )

        val result = flusher.flush()

        assertThat(result).isInstanceOf(FlushResult.Retry::class.java)
        val queued = db.outboxDao().peek(account.serverId, account.userId).single()
        assertThat(queued.attempts).isEqualTo(0)
    }

    // -- Machinery -----------------------------------------------------------------------

    /** The account row with no tokens: what a rejected refresh leaves. */
    private suspend fun lapsedAccountRow() {
        db.serverDao().setActive(
            ServerEntity(
                serverId = SERVER_ID,
                baseUrl = BASE_URL,
                userId = USER_ID,
                username = USERNAME,
                defaultLibraryId = null,
                serverVersion = null,
                isActive = true,
                lanBaseUrl = null,
            ),
        )
    }

    private fun progressRepository(progressDao: ProgressDao) = ProgressRepository(
        client = client(fakeServer(serverPosition = 0.0)),
        progressDao = progressDao,
        outboxDao = db.outboxDao(),
        positionHistoryDao = db.positionHistoryDao(),
        clock = clock,
    )

    private fun authRepository(
        progress: ProgressRepository,
        http: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = fakeServer(0.0),
    ) = AuthRepository(
        client = client(http),
        serverDao = db.serverDao(),
        accountDataDao = db.accountDataDao(),
        tokenStore = tokens,
        progressRepository = progress,
        serverUrlProvider = ActiveServerUrlProvider(
            serverDao = db.serverDao(),
            connectionPrefs = ConnectionPrefs(context),
            race = ConnectionRace(probe = { _, _ -> false }),
        ),
        playingItem = playing,
    )

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = AbsClient(
        serverUrlProvider = StaticServerUrlProvider(BASE_URL),
        tokenStore = InMemoryTokenStore(),
        deviceInfo = DeviceInfoDto(deviceId = "sign-in-seed"),
        nowMs = { nowMs },
        http = HttpClient(MockEngine(handler)) {
            expectSuccess = false
            install(ContentNegotiation) { json(AbsJson) }
        },
    )

    /**
     * A server that answers the status probe and the sign-in. The sign-in carries one
     * position for [ITEM], stamped by another device after anything this one read.
     */
    private fun fakeServer(serverPosition: Double): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
        when {
            request.url.encodedPath.endsWith("/status") -> respond(
                """{"app":"audiobookshelf","serverVersion":"2.36.0","isInit":true,"authMethods":["local"]}""",
                HttpStatusCode.OK,
                JSON,
            )
            request.url.encodedPath.endsWith("/login") -> respond(
                """{"user":{"id":"$USER_ID","username":"$USERNAME","accessToken":"access","refreshToken":"refresh",
                   "mediaProgress":[{"libraryItemId":"$ITEM","duration":$DURATION,"progress":${serverPosition / DURATION},
                   "currentTime":$serverPosition,"isFinished":false,"lastUpdate":$STAMP_ELSEWHERE,"startedAt":1}]},
                   "userDefaultLibraryId":null}""",
                HttpStatusCode.OK,
                JSON,
            )
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private companion object {
        const val BASE_URL = "https://books.example"
        const val USER_ID = "u1"
        const val SERVER_ID = "$BASE_URL#$USER_ID"
        const val USERNAME = "listener"
        const val ITEM = "li_lighthouse_wakes"
        const val DURATION = 3_600.0
        /** Another device's write, an hour after the phone's listening in the lapse. */
        const val STAMP_ELSEWHERE = 1_700_003_600_000L
        val JSON = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
