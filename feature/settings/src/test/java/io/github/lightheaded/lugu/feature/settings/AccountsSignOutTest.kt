package io.github.lightheaded.lugu.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.StoredAccount
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Signing out on the Accounts screen takes the same two taps as in Settings, and no dialog.
 *
 * The first tap arms the button and says what a sign-out deletes. Only the second one
 * signs out.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AccountsSignOutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the first tap arms and says what goes, the second signs out`() {
        var signedOut: String? = null
        compose.setContent {
            MaterialTheme {
                AccountsContent(
                    state = AccountsUiState(accounts = listOf(ACCOUNT)),
                    onBack = {},
                    onAddAccount = {},
                    onSignInAgain = {},
                    onSwitchTo = {},
                    onSignOutOf = { signedOut = it },
                    onDismissError = {},
                )
            }
        }

        compose.onNodeWithText("Sign out").performClick()
        assertThat(signedOut).isNull()
        compose.onNodeWithText(SIGN_OUT_CONSEQUENCE).assertIsDisplayed()

        compose.onNodeWithText("Tap again to sign out").performClick()
        assertThat(signedOut).isEqualTo(SERVER_ID)
    }

    @Test
    fun `a host is named without its scheme`() {
        assertThat(hostOf("https://books.example")).isEqualTo("books.example")
        assertThat(hostOf("http://shelf.example:13378/")).isEqualTo("shelf.example:13378")
    }

    private companion object {
        const val SERVER_ID = "https://books.example#user-1"
        val ACCOUNT = StoredAccount(
            account = ActiveAccount(
                serverId = SERVER_ID,
                baseUrl = "https://books.example",
                userId = "user-1",
                username = "listener",
                defaultLibraryId = null,
            ),
            isActive = true,
            isSignedIn = true,
        )
    }
}
