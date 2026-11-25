package io.github.lightheaded.lugu

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.api.ConnectionRace
import io.github.lightheaded.lugu.core.api.DeviceInfoDto
import io.github.lightheaded.lugu.core.api.InMemoryTokenStore
import io.github.lightheaded.lugu.core.api.StaticServerUrlProvider
import io.github.lightheaded.lugu.core.db.LuguDatabase
import io.github.lightheaded.lugu.core.db.ServerEntity
import io.github.lightheaded.lugu.core.model.AuthTokens
import io.github.lightheaded.lugu.core.sync.ActiveServerUrlProvider
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.ConnectionPrefs
import io.github.lightheaded.lugu.core.sync.InMemoryAccountTokens
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import io.github.lightheaded.lugu.core.sync.WallClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Where the app opens, asked of the real [StartupViewModel] on the real [AuthRepository].
 *
 * Whether the sign-in still stands is not part of the question. A lapsed account opened on
 * a bare sign-in screen, with nothing under it, and its downloads were out of reach
 * (maintainer, 25 November 2026). The test that matters is the lapsed one: an account row
 * with no tokens must open the library, and must also be reported as lapsed, so that the
 * shell offers the sign-in on top. Each half alone would pass on a wrong wiring.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [34],
    // A plain Application, as the other tests here use: lugu's own builds the Hilt graph,
    // which reaches the keystore that no JVM has.
    application = Application::class,
)
class StartupDecisionTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var database: LuguDatabase
    private lateinit var tokens: InMemoryAccountTokens
    private lateinit var auth: AuthRepository
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, LuguDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val client = AbsClient(
            serverUrlProvider = StaticServerUrlProvider(null),
            tokenStore = InMemoryTokenStore(),
            deviceInfo = DeviceInfoDto(deviceId = "startup-decision"),
        )
        tokens = InMemoryAccountTokens()
        auth = AuthRepository(
            client = client,
            serverDao = database.serverDao(),
            accountDataDao = database.accountDataDao(),
            tokenStore = tokens,
            progressRepository = ProgressRepository(
                client = client,
                progressDao = database.progressDao(),
                outboxDao = database.outboxDao(),
                positionHistoryDao = database.positionHistoryDao(),
                clock = WallClock(),
            ),
            serverUrlProvider = ActiveServerUrlProvider(
                serverDao = database.serverDao(),
                connectionPrefs = ConnectionPrefs(context),
                race = ConnectionRace(probe = { _, _ -> false }),
            ),
        )
    }

    @After
    fun tearDown() {
        // Ends the view model's own collection of the database before the database closes.
        store.clear()
        database.close()
    }

    @Test
    fun `a lapsed account opens the library, and the sign-in is offered on top`() {
        runBlocking { database.serverDao().setActive(SERVER) }

        val viewModel = startup()

        assertThat(settled(viewModel)).isEqualTo(StartupState.HasAccount)
        assertThat(lapsedOf(viewModel)).isEqualTo(SERVER.serverId)
    }

    @Test
    fun `a signed-in account opens the library with nothing on top`() {
        runBlocking {
            tokens.saveFor(SERVER.serverId, TOKENS)
            database.serverDao().setActive(SERVER)
        }

        val viewModel = startup()

        assertThat(settled(viewModel)).isEqualTo(StartupState.HasAccount)
        // Given the same time to turn up as the lapsed case, and it must not.
        idleFor(SETTLE_MS)
        assertThat(viewModel.lapsedAccount.value).isNull()
    }

    @Test
    fun `no account opens the sign-in screen`() {
        assertThat(settled(startup())).isEqualTo(StartupState.NoAccount)
    }

    /** A throw here would hold the splash screen on every launch. See [decideStart]. */
    @Test
    fun `a check that throws opens the sign-in screen rather than ending the app`() = runBlocking {
        assertThat(decideStart { error("the database could not be opened") }).isEqualTo(StartupState.NoAccount)
    }

    // -- Machinery -----------------------------------------------------------------------

    private fun startup(): StartupViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = StartupViewModel(auth) as T
        },
    )[StartupViewModel::class.java]

    /** The view model answers on the main thread after a read on another one. */
    private fun settled(viewModel: StartupViewModel): StartupState {
        idleUntil { viewModel.state.value != StartupState.Checking }
        return viewModel.state.value
    }

    private fun lapsedOf(viewModel: StartupViewModel): String? {
        idleUntil { viewModel.lapsedAccount.value != null }
        return viewModel.lapsedAccount.value?.serverId
    }

    private fun idleUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
    }

    private fun idleFor(ms: Long) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        val SERVER = ServerEntity(
            serverId = "https://abs.example.com#u1",
            baseUrl = "https://abs.example.com",
            userId = "u1",
            username = "listener",
            defaultLibraryId = null,
            serverVersion = null,
            isActive = true,
            lanBaseUrl = null,
        )
        val TOKENS = AuthTokens(accessToken = "a", refreshToken = "r", accessTokenExpiresAtMs = Long.MAX_VALUE)
        const val TIMEOUT_MS = 20_000L
        const val SETTLE_MS = 500L
        const val POLL_MS = 10L
    }
}
