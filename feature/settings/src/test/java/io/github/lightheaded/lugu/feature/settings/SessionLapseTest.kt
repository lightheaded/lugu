package io.github.lightheaded.lugu.feature.settings

import android.content.Context
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
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
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.ActiveServerUrlProvider
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.ConnectionPrefs
import io.github.lightheaded.lugu.core.sync.CredentialLossReport
import io.github.lightheaded.lugu.core.sync.FlushResult
import io.github.lightheaded.lugu.core.sync.InMemoryAccountTokens
import io.github.lightheaded.lugu.core.sync.OutboxFlusher
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import io.github.lightheaded.lugu.core.sync.SessionLedgerRepository
import io.github.lightheaded.lugu.core.sync.SignInMemory
import io.github.lightheaded.lugu.core.sync.StoredAccount
import io.github.lightheaded.lugu.core.sync.WallClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A sign-in that ends on its own, a sign-out, and what the sign-in screen does after each.
 *
 * Issue #12 was three faults that made each other worse. A rejected refresh token left the
 * library on screen with no way to sign in again. The only way out was Sign out, which
 * deletes the downloads. And Sign out also forgot the server address, so it had to be typed
 * again. These tests hold each of those apart.
 *
 * The real [AuthRepository] and the real [LoginViewModel], on an in-memory database. The
 * HTTP client points at no server, and nothing here signs in.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SessionLapseTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var database: LuguDatabase
    private lateinit var tokens: InMemoryAccountTokens
    private lateinit var auth: AuthRepository
    private lateinit var memory: SignInMemory
    private lateinit var client: AbsClient

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, LuguDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        client = AbsClient(
            serverUrlProvider = StaticServerUrlProvider(null),
            tokenStore = InMemoryTokenStore(),
            deviceInfo = DeviceInfoDto(deviceId = "session-lapse"),
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
        memory = SignInMemory(context).also { it.forget() }
    }

    @After
    fun tearDown() {
        memory.forget()
        database.close()
    }

    // -- The state the shell watches ----------------------------------------------------

    @Test
    fun `a rejected refresh turns the active account into a lapsed one and keeps its row`() = runBlocking {
        signedIn()
        assertThat(auth.lapsedAccount()).isNull()

        // What `AbsClient.refreshLocked` does when the server refuses the refresh token.
        tokens.clearFor(SERVER_ID)

        val lapsed = withTimeout(TIMEOUT_MS) { auth.observeLapsedAccount().filterNotNull().first() }
        assertThat(lapsed.serverId).isEqualTo(SERVER_ID)
        assertThat(lapsed.baseUrl).isEqualTo(BASE_URL)
        assertThat(lapsed.username).isEqualTo(USERNAME)
        // The row, and so everything keyed by it, is still there to sign in to again.
        assertThat(database.serverDao().byId(SERVER_ID)).isNotNull()
    }

    @Test
    fun `signing in again ends the lapse`() = runBlocking {
        signedIn()
        tokens.clearFor(SERVER_ID)
        withTimeout(TIMEOUT_MS) { auth.observeLapsedAccount().filterNotNull().first() }

        tokens.saveFor(SERVER_ID, TOKENS)

        withTimeout(TIMEOUT_MS) { auth.observeLapsedAccount().first { it == null } }
        assertThat(auth.lapsedAccount()).isNull()
    }

    @Test
    fun `a sign-out is not a lapse`() = runBlocking {
        signedIn()
        val seen = mutableListOf<ActiveAccount?>()
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            auth.observeLapsedAccount().toList(seen)
        }

        auth.signOutOf(SERVER_ID)
        delay(SETTLE_MS)
        watcher.cancel()

        // The row goes before the tokens, so there is no moment with a row and no tokens.
        // If there were, the shell would answer it with the sign-in screen for a session
        // that ended, on top of the one the sign-out opens.
        //
        // One-sided. With the tokens cleared first this fails on most runs but not all,
        // because the database answers on its own threads. With the row first, nothing can
        // read a row without tokens, so this never fails for correct code.
        assertThat(seen.filterNotNull()).isEmpty()
        assertThat(auth.lapsedAccount()).isNull()
    }

    // -- Where the app opens, and what still works ---------------------------------------

    /**
     * The start decision reads [AuthRepository.hasActiveAccount]. A lapsed account must
     * still answer yes, so the app opens on its library and its downloads, and only a
     * device with no account at all opens on the sign-in screen (maintainer, 25 November
     * 2026: "when the session is expired, I should still be able to listen to offline
     * files").
     */
    @Test
    fun `a lapsed account still opens the library, and no account opens the sign-in screen`() = runBlocking {
        assertThat(auth.hasActiveAccount()).isFalse()

        signedIn()
        assertThat(auth.hasActiveAccount()).isTrue()

        tokens.clearFor(SERVER_ID)
        // Signed out in the sense that nothing can reach the server, and still an account
        // whose library is on the phone.
        assertThat(auth.isSignedIn()).isFalse()
        assertThat(auth.hasActiveAccount()).isTrue()

        auth.signOutOf(SERVER_ID)
        assertThat(auth.hasActiveAccount()).isFalse()
    }

    /**
     * A position listened to while the session was over stays in the outbox with every
     * attempt left. The flusher drops an entry that fails when its attempts are used up,
     * and a blocked send used to count as one. A lapse of a day used them all up, so the
     * first ordinary failure after the next sign-in, a moment without signal, dropped the
     * position instead of trying it again.
     */
    @Test
    fun `a position written while lapsed waits in the outbox with its attempts intact`() = runBlocking {
        signedIn()
        tokens.clearFor(SERVER_ID)
        // A client that knows the server but holds no token: the state a rejected refresh
        // leaves. Every send stops at the token, before the network.
        val lapsedClient = AbsClient(
            serverUrlProvider = StaticServerUrlProvider(BASE_URL),
            tokenStore = InMemoryTokenStore(),
            deviceInfo = DeviceInfoDto(deviceId = "session-lapse"),
        )
        val progress = ProgressRepository(
            client = lapsedClient,
            progressDao = database.progressDao(),
            outboxDao = database.outboxDao(),
            positionHistoryDao = database.positionHistoryDao(),
            clock = WallClock(),
        )
        val flusher = OutboxFlusher(
            outboxDao = database.outboxDao(),
            progressRepository = progress,
            sessionLedgerRepository = SessionLedgerRepository(
                client = lapsedClient,
                dao = database.sessionLedgerDao(),
                deviceInfo = DeviceInfoDto(deviceId = "session-lapse"),
                clock = WallClock(),
            ),
            authRepository = auth,
            clock = WallClock(),
        )
        val account = requireNotNull(auth.account())
        progress.record(account, ITEM_ID, null, positionSec = 600.0, durationSec = 3_600.0)

        // More runs than the flusher allows attempts.
        repeat(OutboxFlusher.MAX_ATTEMPTS + 3) {
            assertThat(flusher.flush()).isEqualTo(FlushResult.AuthRequired)
        }

        val queued = database.outboxDao().peek(account.serverId, account.userId)
        assertThat(queued).hasSize(1)
        assertThat(queued.single().attempts).isEqualTo(0)
        assertThat(queued.single().payloadJson).contains("600")
        // And the position itself is still the one in Room, waiting to be pushed.
        assertThat(progress.get(account, ITEM_ID, null)?.currentTimeSec).isEqualTo(600.0)
    }

    // -- The sign-in screen --------------------------------------------------------------

    @Test
    fun `the sign-in screen on top of another screen offers a way back, and says the downloads still play`() {
        runBlocking { signedIn() }
        runBlocking { tokens.clearFor(SERVER_ID) }
        var notNow = 0

        showLogin(onNotNow = { notNow += 1 })

        compose.onNodeWithText(SESSION_ENDED_NOTICE).assertIsDisplayed()
        compose.onNodeWithText(NOT_NOW_LABEL).assertIsDisplayed().performClick()
        assertThat(notNow).isEqualTo(1)
    }

    @Test
    fun `the sign-in screen with nothing under it offers no way back`() {
        runBlocking { signedIn() }
        runBlocking { tokens.clearFor(SERVER_ID) }

        showLogin()

        // The fields are drawn, so the screen is ready, and there is no button that would
        // lead nowhere.
        compose.onNodeWithContentDescription("Password").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText(NOT_NOW_LABEL)).isEmpty()
    }

    @Test
    fun `a lapse opens the sign-in screen filled in, with the reason above it`() {
        runBlocking { signedIn() }
        runBlocking { tokens.clearFor(SERVER_ID) }

        showLogin()

        compose.onNodeWithText(SESSION_ENDED_NOTICE).assertIsDisplayed()
        compose.onNodeWithContentDescription("Server address").assertTextContains(BASE_URL)
        compose.onNodeWithContentDescription("Username").assertTextContains(USERNAME)
    }

    @Test
    fun `Sign in again on the Accounts screen fills in the account it names`() {
        // Not the active account: the Accounts screen can name any lapsed row.
        runBlocking {
            database.serverDao().setActive(server(OTHER_ID, OTHER_URL, "someone-else"))
            tokens.saveFor(OTHER_ID, TOKENS)
            database.serverDao().upsert(server(SERVER_ID, BASE_URL, USERNAME).copy(isActive = false))
        }

        showLogin(account = SERVER_ID)

        // Not the notice of the account in use: this account's downloads play only once it
        // is in use again, so the screen must not say that they still play.
        compose.onNodeWithText(OTHER_SESSION_ENDED_NOTICE).assertIsDisplayed()
        assertThat(compose.onAllNodesWithText(SESSION_ENDED_NOTICE)).isEmpty()
        compose.onNodeWithContentDescription("Server address").assertTextContains(BASE_URL)
        compose.onNodeWithContentDescription("Username").assertTextContains(USERNAME)
    }

    @Test
    fun `after a sign-out the sign-in screen still knows the server`() {
        memory.remember(BASE_URL, USERNAME)

        showLogin()

        compose.onNodeWithContentDescription("Server address").assertTextContains(BASE_URL)
        compose.onNodeWithContentDescription("Username").assertTextContains(USERNAME)
        // An ordinary sign-out needs no explanation.
        assertThat(compose.onAllNodesWithText(SESSION_ENDED_NOTICE)).isEmpty()
        assertThat(compose.onAllNodesWithText(OTHER_SESSION_ENDED_NOTICE)).isEmpty()
    }

    @Test
    fun `forgetting the server empties the fields and the memory`() {
        memory.remember(BASE_URL, USERNAME)
        val viewModel = showLogin()

        compose.onNodeWithContentDescription("Forget this server").performClick()

        assertThat(viewModel.state.value.serverUrl).isEmpty()
        assertThat(viewModel.state.value.username).isEmpty()
        assertThat(memory.last()).isNull()
    }

    /**
     * What an autofill service reads. 1Password left the username empty, and the address
     * field, the first text field with no type of its own, is the likely reason: it read
     * as the username.
     */
    @Test
    fun `autofill sees the username and the password and leaves the address alone`() {
        showLogin()

        val address = compose.onNodeWithContentDescription("Server address").fetchSemanticsNode().config
        val username = compose.onNodeWithContentDescription("Username").fetchSemanticsNode().config
        val password = compose.onNodeWithContentDescription("Password").fetchSemanticsNode().config

        assertThat(username.getOrNull(SemanticsProperties.ContentType)).isEqualTo(ContentType.Username)
        assertThat(password.getOrNull(SemanticsProperties.ContentType)).isEqualTo(ContentType.Password)
        assertThat(address.getOrNull(SemanticsProperties.ContentType)).isEqualTo(SERVER_ADDRESS_CONTENT_TYPE)
        assertThat(address.getOrNull(SemanticsProperties.ContentDataType)).isEqualTo(ContentDataType.None)
    }

    // -- The Accounts row ----------------------------------------------------------------

    @Test
    fun `a lapsed account row offers to sign in again`() {
        var asked: String? = null
        compose.setContent {
            AccountsContent(
                state = AccountsUiState(
                    accounts = listOf(
                        StoredAccount(
                            account = ActiveAccount(SERVER_ID, BASE_URL, "u1", USERNAME, null),
                            isActive = true,
                            isSignedIn = false,
                        ),
                    ),
                ),
                onBack = {},
                onAddAccount = {},
                onSignInAgain = { asked = it },
                onSwitchTo = {},
                onSignOutOf = {},
                onDismissError = {},
            )
        }

        compose.onNodeWithText("Sign in again").performClick()

        assertThat(asked).isEqualTo(SERVER_ID)
    }

    // -- Machinery -----------------------------------------------------------------------

    private suspend fun signedIn() {
        tokens.saveFor(SERVER_ID, TOKENS)
        database.serverDao().setActive(server(SERVER_ID, BASE_URL, USERNAME))
    }

    private fun server(id: String, url: String, name: String) = ServerEntity(
        serverId = id,
        baseUrl = url,
        userId = id.substringAfter('#'),
        username = name,
        defaultLibraryId = null,
        serverVersion = null,
        isActive = true,
        lanBaseUrl = null,
    )

    private fun showLogin(account: String? = null, onNotNow: (() -> Unit)? = null): LoginViewModel {
        val viewModel = LoginViewModel(
            context = context,
            authRepository = auth,
            connectionPrefs = ConnectionPrefs(context),
            credentialLosses = CredentialLossReport(),
            signInMemory = memory,
            client = client,
            savedStateHandle = SavedStateHandle(
                account?.let { mapOf(LoginArgs.ACCOUNT to LoginArgs.encode(it)) } ?: emptyMap(),
            ),
        )
        compose.setContent { LoginScreen(onSignedIn = {}, onNotNow = onNotNow, viewModel = viewModel) }
        compose.waitUntil(TIMEOUT_MS) { viewModel.state.value.ready }
        compose.waitForIdle()
        return viewModel
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithText(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes()

    private companion object {
        const val BASE_URL = "https://abs.example.com"
        const val USERNAME = "listener"
        const val SERVER_ID = "$BASE_URL#u1"
        const val OTHER_URL = "https://other.example.com"
        const val OTHER_ID = "$OTHER_URL#u2"
        const val ITEM_ID = "li_lighthouse_wakes"
        val TOKENS = AuthTokens(accessToken = "a", refreshToken = "r", accessTokenExpiresAtMs = Long.MAX_VALUE)
        const val TIMEOUT_MS = 20_000L
        const val SETTLE_MS = 500L
    }
}
