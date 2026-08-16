package io.github.lightheaded.lugu

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Catches the app not starting at all.
 *
 * Nothing else in the suite would notice. Three of the ways lugu can fail to launch are
 * invisible to a unit test and to a compile: Hilt's graph failing to build at runtime,
 * WorkManager's initialiser being removed from the manifest without Hilt's replacement
 * taking over, and R8 having stripped something that is only reached reflectively. All
 * three land as a crash before the first frame.
 *
 * This is also the one instrumented test that is worth running with no server at all,
 * which makes it the test that keeps the emulator job honest: a green emulator run with
 * every other test skipped still proves the app starts. *
 * The method names here are underscored rather than the backticked sentences the JVM suites
 * use. A name with spaces in it needs DEX version 040, which needs minSdk 30; lugu's minSdk
 * is 26, so the test APK will not dex with them.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class LaunchSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun the_app_launches_and_settles_on_a_screen() {
        compose.waitUntil(LAUNCH_TIMEOUT_MS) {
            compose.onAllNodes(hasText(SIGN_IN_PROMPT)).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasContentDescription("Settings")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * The sign-in branch, asserted properly rather than skipped past.
     *
     * On a fresh emulator — which is what CI runs on — nothing is stored, so this is the
     * branch taken. On a phone that is already signed in the shell comes up instead, and
     * there is nothing to check here that the first test has not already checked.
     */
    @Test
    fun a_signed_out_launch_asks_for_a_server_a_username_and_a_password() {
        compose.waitUntil(LAUNCH_TIMEOUT_MS) {
            compose.onAllNodes(hasText(SIGN_IN_PROMPT)).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasContentDescription("Settings")).fetchSemanticsNodes().isNotEmpty()
        }

        val signedOut = compose.onAllNodes(hasText(SIGN_IN_PROMPT)).fetchSemanticsNodes().isNotEmpty()
        if (!signedOut) return

        compose.onNodeWithContentDescription("Server address").assertIsDisplayed()
        compose.onNodeWithContentDescription("Username").assertIsDisplayed()
        compose.onNodeWithContentDescription("Password").assertIsDisplayed()
        compose.onNodeWithText("Sign in").assertIsDisplayed()
    }

    private companion object {
        const val SIGN_IN_PROMPT = "Sign in to your Audiobookshelf server"

        /** A cold start on a slow emulator, with room for the graph to be built. */
        const val LAUNCH_TIMEOUT_MS = 30_000L
    }
}
