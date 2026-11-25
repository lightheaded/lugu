package io.github.lightheaded.lugu

import android.content.Context
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.db.LuguDatabase
import io.github.lightheaded.lugu.core.db.ServerEntity
import io.github.lightheaded.lugu.core.model.AuthTokens
import io.github.lightheaded.lugu.core.sync.EncryptedTokenStore
import io.github.lightheaded.lugu.feature.settings.NOT_NOW_LABEL
import io.github.lightheaded.lugu.feature.settings.SESSION_ENDED_NOTICE
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A session that ended never blocks the downloads: what the shell does at launch.
 *
 * A lapsed account is an active `server` row whose tokens are gone, which is what a
 * rejected refresh leaves. It used to start on a bare sign-in screen with nothing under it,
 * so Back closed the app and the downloads were out of reach (maintainer, 25 November 2026).
 * Now it starts on Home and the sign-in is pushed on top, once, with a "Not now" that
 * returns to Home. The parts each have a JVM test. The join — the start destination, the
 * lapse effect that waits for the graph, the back stack and the saved state that survives a
 * rebuild — exists only in `MainActivity`, so it is watched here.
 *
 * No server is needed, and none is reached: the row points at a domain that cannot
 * resolve, and it has no token, so nothing is sent. Whatever the device held — its active
 * account, and any token stored under this test's id — is put back afterwards.
 *
 * The method names here are underscored rather than the backticked sentences the JVM suites
 * use. A name with spaces in it needs DEX version 040, which needs minSdk 30; lugu's minSdk
 * is 26, so the test APK will not dex with them.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class LapsedSessionTest {

    @get:Rule(order = 0)
    val notifications = grantNotificationPermission()

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: LuguDatabase
    private lateinit var tokens: EncryptedTokenStore
    private var displacedServer: ServerEntity? = null
    private var displacedTokens: AuthTokens? = null
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun takeTheDeviceAside() {
        db = LuguDatabase.build(context)
        tokens = EncryptedTokenStore(context, db.serverDao())
        runBlocking {
            displacedServer = db.serverDao().active()
            displacedTokens = tokens.tokensFor(TEST_SERVER_ID)
            db.serverDao().clearActive()
            // No token under this id, whatever an earlier run left: that is the lapse.
            tokens.clearFor(TEST_SERVER_ID)
        }
        wipeTestRows()
    }

    @After
    fun restoreTheDevice() {
        scenario?.close()
        scenario = null
        runBlocking {
            runCatching {
                wipeTestRows()
                displacedTokens?.let { tokens.saveFor(TEST_SERVER_ID, it) }
                displacedServer?.let { db.serverDao().setActive(it) }
            }
        }
        db.close()
    }

    /** (a) and (b): Home under, the sign-in with its reason on top, and "Not now" to Home. */
    @Test
    fun a_lapsed_account_opens_home_with_the_sign_in_on_top_and_not_now_returns_to_home() {
        seedLapsedAccount()
        launch()

        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(SIGN_IN_PROMPT)) }
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(SESSION_ENDED_NOTICE)) }
        // The button is offered only when a screen is under the sign-in, so its presence is
        // the proof that the app started on Home and not on the sign-in screen.
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(NOT_NOW_LABEL)) }

        notNow()

        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasContentDescription(SETTINGS)) }
        assertThat(nodeExists(hasText(SIGN_IN_PROMPT))).isFalse()
    }

    /** Back does the same as "Not now", and does not close the app. */
    @Test
    fun back_from_the_sign_in_returns_to_home() {
        seedLapsedAccount()
        launch()
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(NOT_NOW_LABEL)) }
        compose.waitForIdle()

        scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasContentDescription(SETTINGS)) }
        assertThat(nodeExists(hasText(SIGN_IN_PROMPT))).isFalse()
    }

    /**
     * (c): the lapse is answered once. A rebuild with the sign-in on top keeps one sign-in,
     * not two, and a rebuild after "Not now" does not push it again.
     */
    @Test
    fun a_rebuild_does_not_push_the_sign_in_again() {
        seedLapsedAccount()
        launch()
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(NOT_NOW_LABEL)) }

        scenario?.recreate()
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(NOT_NOW_LABEL)) }
        settle()
        // A second sign-in pushed by the rebuild would sit under this one, and "Not now"
        // would land on it rather than on Home.
        notNow()
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasContentDescription(SETTINGS)) }

        scenario?.recreate()
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasContentDescription(SETTINGS)) }
        // Given the time the lapse effect needs to answer, and it must not.
        settle()
        assertThat(nodeExists(hasText(SIGN_IN_PROMPT))).isFalse()
        assertThat(nodeExists(hasContentDescription(SETTINGS))).isTrue()
    }

    /** (d): with no account there is nothing under the sign-in, so there is no "Not now". */
    @Test
    fun with_no_account_the_sign_in_offers_no_way_back() {
        launch()

        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(SIGN_IN_PROMPT)) }
        settle()
        assertThat(nodeExists(hasText(NOT_NOW_LABEL))).isFalse()
        assertThat(nodeExists(hasText(SESSION_ENDED_NOTICE))).isFalse()
    }

    // -- Machinery -----------------------------------------------------------------------

    private fun seedLapsedAccount() = runBlocking {
        db.serverDao().setActive(
            ServerEntity(
                serverId = TEST_SERVER_ID,
                baseUrl = TEST_SERVER_URL,
                userId = TEST_USER_ID,
                username = "tester",
                defaultLibraryId = null,
                serverVersion = null,
                isActive = true,
            ),
        )
    }

    /** After the seed, for the reason `docs/qa/instrumented.md` gives. */
    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /**
     * Waits for the screen to settle first. "Not now" acts only from a resumed screen, so a
     * tap during the push transition would be dropped on purpose.
     */
    private fun notNow() {
        compose.waitForIdle()
        compose.onNodeWithText(NOT_NOW_LABEL).performClick()
    }

    private fun settle() {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
    }

    private fun nodeExists(matcher: androidx.compose.ui.test.SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun wipeTestRows() {
        db.openHelper.writableDatabase
            .execSQL("DELETE FROM `server` WHERE serverId = ?", arrayOf<Any>(TEST_SERVER_ID))
    }

    private companion object {
        const val TEST_SERVER_URL = "https://books.example"
        const val TEST_USER_ID = "lapsed-session-test-user"
        const val TEST_SERVER_ID = "$TEST_SERVER_URL#$TEST_USER_ID"

        const val SIGN_IN_PROMPT = "Sign in to your Audiobookshelf server"
        const val SETTINGS = "Settings"
        const val UI_TIMEOUT_MS = 30_000L

        /** Long enough for the lapse effect to answer a rebuild, on a slow emulator. */
        const val SETTLE_MS = 2_000L
    }
}
