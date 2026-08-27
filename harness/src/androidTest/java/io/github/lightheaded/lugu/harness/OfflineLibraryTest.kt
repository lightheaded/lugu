package io.github.lightheaded.lugu.harness

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The claim the whole app is built on: the database is the source of truth.
 *
 * One sentence from the M0 plan, and the last sign-in check on it that a machine could take:
 * force-stop lugu, take the network away, open it again, and the full library still renders
 * and no sign-in screen appears. A person who lands on a login form after a flight loses
 * their library, whatever the database still holds.
 *
 * ### Why it belongs here
 *
 * `:app` cannot make this check. Instrumentation runs inside the process under test, so
 * `am force-stop` on lugu would take the runner with it. This module force-stops lugu
 * already.
 *
 * ### The account has to be real
 *
 * `isSignedIn` is an active `server` row **and** a token in encrypted preferences, so a
 * seeded row alone lands on the login screen and every assertion after it fails at "there is
 * no Library tab" — true, and about the fixture rather than about the app. The harness cannot
 * plant a token anyway: lugu's preferences belong to lugu. So the account here is real. lugu
 * signs itself in from its own build configuration, the harness taps the button that is
 * already filled in, and the library that renders at the end is the library that the first
 * sync mirrored into Room. That is why this check needs a server, and why it skips without
 * one. CI seeds a server, so it does not skip there.
 *
 * ### What "no network" means, and what it does not
 *
 * [OfflineVpnService] holds the whole argument. In short: lugu loses every route to every
 * network, on API 26 and on API 36, by one command. The radios stay on, so this does not
 * exercise anything that reads the airplane-mode flag, and lugu still sees a default network
 * on which every request fails. The manual line keeps the airplane-mode wording, because
 * airplane mode is also a state a person's phone reaches and this is not exactly it.
 *
 * ### What renders, and what is only claimed
 *
 * "The full library still renders" is asserted as the title in `lugu.test.playQuery` being on
 * the Library tab. This check only reads that title. Nothing plays, so it moves nobody's place
 * in a book, unlike the two resumption tests. One known title is what a machine can check
 * without a name or a count from a real shelf. "Full" stays a manual claim.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class OfflineLibraryTest {

    @Before
    fun luguIsInstalled() {
        assumeTrue(HarnessConfig.NO_LUGU, Lugu.isInstalled)

        // Granted from outside because the usual GrantPermissionRule grants to the package
        // under test, which here is the harness rather than lugu. Without it the system's
        // permission dialog comes up over lugu on first launch and every tap after it lands
        // on the wrong window. It exists only from Android 13; below that this does nothing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Shell.run("pm grant ${Lugu.PACKAGE} android.permission.POST_NOTIFICATIONS")
        }
    }

    /**
     * Puts the network back, whatever happened.
     *
     * A test that leaves a device without a network poisons every test after it, including
     * another job's. This runs after a failure as well as after a pass, and every step of it
     * is best effort — see [LuguOffline.restore].
     */
    @After
    fun putLuguBackOnTheNetwork() {
        runCatching { LuguOffline.restore() }.onFailure { Log.w(TAG, "cleanup did not complete", it) }
    }

    /** Force-stop, no network, reopen. */
    @Test
    fun the_library_still_renders_after_a_force_stop_with_no_network() {
        assumeTrue(HarnessConfig.NO_SERVER, HarnessConfig.hasServer)
        assumeTrue(HarnessConfig.NO_QUERY, HarnessConfig.canPlay)

        val title = HarnessConfig.playQuery

        signInAndMirrorTheLibrary(title)

        Lugu.forceStop()
        assertWithMessage("lugu's process survived am force-stop")
            .that(Await.until(KILL_TIMEOUT_MS) { Lugu.pid() == null })
            .isTrue()

        assertWithMessage(
            "the harness could not take lugu off the network, so this tested nothing about " +
                "offline behaviour. This is a failure of the harness, not of lugu.",
        ).that(LuguOffline.cut()).isNull()

        Lugu.launch()
        assertWithMessage("lugu did not come back after a force-stop with no network")
            .that(LuguUi.awaitWindow())
            .isTrue()
        LuguUi.openLibraryTab()

        val screen = Await.notNull(OFFLINE_RENDER_TIMEOUT_MS) {
            when {
                LuguUi.asksToSignIn() -> Screen.SIGN_IN
                LuguUi.shows(title) -> Screen.LIBRARY
                else -> null
            }
        }

        assertWithMessage(
            "lugu asked for credentials after a force-stop with no network. The account is " +
                "still in the database, so this is the offline-first promise breaking: a " +
                "stored session must stay valid until a request says otherwise.",
        ).that(screen).isNotEqualTo(Screen.SIGN_IN)
        assertWithMessage(
            "nothing from the library was on screen ${OFFLINE_RENDER_TIMEOUT_MS}ms after " +
                "lugu opened again with no network. The title was on screen before the " +
                "force-stop, so the mirror in Room holds it and the screen does not read it.",
        ).that(screen).isEqualTo(Screen.LIBRARY)

        // Last, because it is the one way a pass here can be a lie. If the tunnel went away
        // during the run, the library may have come from the server after all.
        assertWithMessage("lugu got its network back before the library rendered")
            .that(LuguOffline.isCut)
            .isTrue()

        Log.i(TAG, "the library rendered with lugu off the network on API ${Build.VERSION.SDK_INT}")
    }

    /**
     * Gets lugu to the state the check starts from: signed in, with the library mirrored.
     *
     * The screen is what tells the harness that the mirror arrived. Room belongs to another
     * app, so the harness cannot read it, and the title on the Library tab is the same fact
     * seen from outside. Waiting here rather than after the kill keeps "the library never
     * arrived" a different failure from "the library did not come back", and they are
     * different bugs.
     */
    private fun signInAndMirrorTheLibrary(title: String) {
        Lugu.launch()
        LuguUi.signInIfAsked()
        LuguUi.openLibraryTab()

        assertWithMessage(
            "the title in lugu.test.playQuery was not on screen ${LIBRARY_TIMEOUT_MS}ms after " +
                "sign-in. Either it is not in this server's library, or the first sync " +
                "after sign-in mirrored no items. This tested nothing about offline behaviour.",
        ).that(Await.until(LIBRARY_TIMEOUT_MS) { LuguUi.shows(title) }).isTrue()
    }

    /** What came up when lugu was opened with no network. */
    private enum class Screen { LIBRARY, SIGN_IN }

    private companion object {
        const val TAG = "LuguHarness"

        const val KILL_TIMEOUT_MS = 15_000L

        /** A first sync, over whatever network CI has, on an emulator that has just booted. */
        const val LIBRARY_TIMEOUT_MS = 90_000L

        /**
         * A cold start with no network: Hilt's graph, a Room read and one screen.
         *
         * Generous on purpose. Nothing on this path waits for a request — that is the claim —
         * but the first sync starts anyway and dies on a name lookup that has nowhere to go,
         * and a slow emulator gets to be slow without turning a pass into a failure.
         */
        const val OFFLINE_RENDER_TIMEOUT_MS = 60_000L
    }
}
