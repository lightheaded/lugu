package io.github.lightheaded.lugu.feature.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.db.LuguDatabase
import io.github.lightheaded.lugu.core.sync.CrashReportingPrefs
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the settings screen does when it is used, as opposed to what it looks like.
 *
 * The real [SettingsScreen] over the real [SettingsViewModel], so a search hit here is a
 * hit on the entry list that ships, not on a copy of it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SettingsBehaviourTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var database: LuguDatabase
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, LuguDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        viewModel = settingsViewModelForTest(context, database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * The switch alone is a small target at the far edge of the row. A tap on the title
     * is the tap most people make, and before this it did nothing.
     */
    @Test
    fun `a tap on a switch row's title flips the switch`() {
        viewModel.onQueryChange("crash reports")
        show()

        compose.onNodeWithText("Send crash reports").assertIsOff()
        compose.onNodeWithText("Send crash reports", useUnmergedTree = true).performClick()

        compose.onNodeWithText("Send crash reports").assertIsOn()
        assertThat(CrashReportingPrefs(context).isEnabled()).isTrue()
    }

    /** One row, one control: TalkBack reads the title and the state together, once. */
    @Test
    fun `a switch row is one control, not a title and a switch`() {
        viewModel.onQueryChange("crash reports")
        show()

        compose.onNodeWithText("Send crash reports").assert(isToggleable())
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
    }

    /**
     * The words the Connection screen's own users arrive with. The link sits inside the
     * Account entry, and search reads only an entry's title, category and keywords, so
     * these words have to be in its keywords. "Nothing matches" would tell them lugu has
     * no such setting.
     */
    @Test
    fun `searching for what sits in front of the server finds the Connection screen`() {
        show()
        for (query in listOf("connection", "proxy", "certificate", "header", "cloudflare")) {
            viewModel.onQueryChange(query)
            compose.waitForIdle()

            compose.onNodeWithText("Connection").assertExists("No Connection link for \"$query\"")
            compose.onAllNodesWithText("Nothing matches", substring = true).assertCountEquals(0)
        }
    }

    private fun show() {
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    onBack = {},
                    onSignedOut = {},
                    onOpenLicenses = {},
                    viewModel = viewModel,
                )
            }
        }
    }
}
