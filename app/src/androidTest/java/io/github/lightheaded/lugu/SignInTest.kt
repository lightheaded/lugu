package io.github.lightheaded.lugu

import android.content.Context
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.db.LuguDatabase
import io.github.lightheaded.lugu.core.db.ServerEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Signing in: what it says when it fails, and what it does when it works.
 *
 * Both halves used to be manual lines on the M0 checklist, and both are about the first
 * ninety seconds of using lugu — the part nobody re-tests once it has worked once.
 *
 * **A wrong password must say so.** Every other client of this server has been reported at
 * some point for answering a rejected password with a reachability error, and the two send
 * a person in opposite directions: one means "check what you typed", the other means "check
 * your network, your proxy, your DNS". lugu probes the address before it sends credentials
 * precisely so the two cannot be confused, and this is what holds that apart.
 *
 * **A successful sign-in must fill the mirror.** Until 17 August it did not: the only
 * on-demand sync belongs to the Library tab, so a new account's Home was empty and stayed
 * empty, and so was everything else reading Room — including the car, which cannot tap a
 * tab. That is asserted here against Room rather than against the screen, because the screen
 * is not the claim: the claim is that the items are on the phone.
 *
 * Needs the seeded server, so these skip when there is none. See
 * [docs/qa/instrumented.md](../../../../../../../docs/qa/instrumented.md).
 *
 * The method names here are underscored rather than the backticked sentences the JVM suites
 * use. A name with spaces in it needs DEX version 040, which needs minSdk 30; lugu's minSdk
 * is 26, so the test APK will not dex with them.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SignInTest {

    @get:Rule(order = 0)
    val notifications = grantNotificationPermission()

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: LuguDatabase
    private var displacedServer: ServerEntity? = null
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun signOut() {
        assumeTrue(TestServerConfig.NO_SERVER, TestServerConfig.hasServer)
        db = LuguDatabase.build(context)
        runBlocking {
            displacedServer = db.serverDao().active()
            db.serverDao().clearActive()
        }
    }

    @After
    fun restoreTheDevice() {
        scenario?.close()
        scenario = null
        if (!TestServerConfig.hasServer) return
        runBlocking {
            // Only the active flag is put back. Nothing seeded by signing in is deleted:
            // these rows belong to a real account, and a mirror is expensive to rebuild.
            runCatching { displacedServer?.let { db.serverDao().setActive(it) } }
        }
        db.close()
    }

    @Test
    fun a_wrong_password_says_so_rather_than_blaming_the_network() {
        launchSignedOut()

        compose.onNodeWithContentDescription(PASSWORD).performTextClearance()
        compose.onNodeWithContentDescription(PASSWORD).performTextInput("not-the-password")
        compose.onNodeWithText(SIGN_IN).performClick()

        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(WRONG_PASSWORD)) }
        // The failure that matters is the other message winning: an address that answered a
        // probe a second ago has not become unreachable, and saying so sends somebody to
        // look at their proxy instead of at what they typed.
        assertThat(nodeExists(hasText(WRONG_PASSWORD))).isTrue()
    }

    /**
     * The whole library, on the phone, without a tab being tapped.
     *
     * Asserted against Room because that is what was empty. Every surface that was broken
     * by this read from there — Home's shelves, the car's browse tree, and the search behind
     * "play X on lugu" — and none of them can be reached by tapping Library first.
     */
    @Test
    fun signing_in_mirrors_the_library_without_opening_the_library_tab() {
        launchSignedOut()

        compose.onNodeWithText(SIGN_IN).performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasContentDescription(SETTINGS)) }

        var items = 0
        val deadline = System.currentTimeMillis() + MIRROR_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            items = runBlocking {
                db.serverDao().active()?.let { server ->
                    db.libraryItemDao().count(server.serverId, server.userId)
                } ?: 0
            }
            if (items > 0) break
            Thread.sleep(POLL_MS)
        }

        assertThat(items).isGreaterThan(0)
    }

    // -- Machinery -----------------------------------------------------------------------

    /**
     * The fields arrive prefilled from the same `BuildConfig` values that configure this
     * test, so signing in is one tap and nothing here ever types a real password.
     */
    private fun launchSignedOut() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(UI_TIMEOUT_MS) { nodeExists(hasText(SIGN_IN_PROMPT)) }
    }

    private fun nodeExists(matcher: androidx.compose.ui.test.SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val SIGN_IN_PROMPT = "Sign in to your Audiobookshelf server"
        const val SIGN_IN = "Sign in"
        const val PASSWORD = "Password"
        const val SETTINGS = "Settings"
        const val WRONG_PASSWORD = "Wrong username or password"

        const val UI_TIMEOUT_MS = 30_000L

        /** A first mirror of a whole library, on a cold emulator, over a container. */
        const val MIRROR_TIMEOUT_MS = 120_000L
        const val POLL_MS = 500L
    }
}
