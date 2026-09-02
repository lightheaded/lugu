package io.github.lightheaded.lugu.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.StoredAccount
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Catches the accounts screen losing the two things that make it safe to use.
 *
 * The first is which account is in force. With two libraries on one device, a screen that
 * does not make the active one obvious is a screen that gets somebody listening in the
 * wrong library and reporting it as lost progress.
 *
 * The second is the account whose sign-in has lapsed. A refresh token expires after thirty
 * days and encrypted storage can be rebuilt by a device restore, so a stored account with
 * no usable tokens is an ordinary state rather than an edge case. It has to say so on the
 * row, because the alternative is a tap that switches to a library which then cannot load.
 *
 * Photographs the real [AccountsContent]. The view model is not built here, which is the
 * point of the screen being split in two.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rGB-" + RobolectricDeviceQualifiers.Pixel5)
class AccountsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `two accounts show which one is in force`() {
        capture("accounts_light", dark = false) { AccountsPreview(TWO_ACCOUNTS) }
    }

    @Test
    fun `two accounts read correctly in the dark`() {
        capture("accounts_dark", dark = true) { AccountsPreview(TWO_ACCOUNTS) }
    }

    /**
     * One account is the state every install starts in, and the screen still has to be
     * worth opening: it is where "add another account" lives.
     */
    @Test
    fun `one account still offers a second`() {
        capture("accounts_single_light", dark = false) { AccountsPreview(listOf(ACTIVE)) }
    }

    /** A failure has to appear without moving the list under the reader's finger. */
    @Test
    fun `a failed switch says so over the list`() {
        capture("accounts_error_light", dark = false) {
            AccountsPreview(TWO_ACCOUNTS, error = "That account is not on this device")
        }
    }

    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { AccountsTheme(dark = dark, content = content) }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    private companion object {
        val ACTIVE = StoredAccount(
            account = ActiveAccount(
                serverId = "https://books.example#user-1",
                baseUrl = "https://books.example",
                userId = "user-1",
                username = "listener",
                defaultLibraryId = "lib-1",
            ),
            isActive = true,
            isSignedIn = true,
        )

        /** The lapsed one, which is the row this screen exists to be honest about. */
        val LAPSED = StoredAccount(
            account = ActiveAccount(
                serverId = "https://shelf.example#user-2",
                baseUrl = "https://shelf.example",
                userId = "user-2",
                username = "second listener",
                defaultLibraryId = null,
            ),
            isActive = false,
            isSignedIn = false,
        )

        val TWO_ACCOUNTS = listOf(ACTIVE, LAPSED)
    }
}

@Composable
private fun AccountsPreview(accounts: List<StoredAccount>, error: String? = null) {
    AccountsContent(
        state = AccountsUiState(accounts = accounts, error = error),
        onBack = {},
        onAddAccount = {},
        onSwitchTo = {},
        onSignOutOf = {},
        onDismissError = {},
    )
}

/** lugu's palette without dynamic colour, matching the other screenshot tests. */
@Composable
private fun AccountsTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) {
        darkColorScheme(primary = Color(0xFFEDC08B), secondary = Color(0xFFD6C3AE))
    } else {
        lightColorScheme(primary = Color(0xFF7A5A36), secondary = Color(0xFF6B5D4D))
    }
    MaterialTheme(colorScheme = colors) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}
