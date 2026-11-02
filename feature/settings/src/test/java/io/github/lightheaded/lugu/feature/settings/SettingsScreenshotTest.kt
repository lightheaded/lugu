package io.github.lightheaded.lugu.feature.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.lightheaded.lugu.core.db.LuguDatabase
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Catches a silent change to the settings surface, and to what its search box does.
 *
 * Settings is the one screen here that is genuinely long — enough that nobody scrolls it
 * looking for anything, which is why it has a search box at all. Two things are worth a
 * picture: that the list arrives grouped under its category headings, and that typing
 * narrows it to the matching rows and their headings rather than leaving a heading
 * stranded over nothing.
 *
 * This is the real [SettingsScreen] with the real [SettingsViewModel], not a stand-in.
 * [settingsViewModelForTest] builds it without Hilt, and nothing here contacts anything.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class SettingsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var database: LuguDatabase
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LuguDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        viewModel = settingsViewModelForTest(context, database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `settings arrives grouped under its category headings`() {
        capture("settings_light", dark = false)
    }

    @Test
    fun `settings reads correctly in the dark`() {
        capture("settings_dark", dark = true)
    }

    @Test
    fun `searching narrows to the matching rows and keeps their headings`() {
        capture("settings_search_light", dark = false, query = "sleep")
    }

    @Test
    fun `a search that matches nothing says so`() {
        capture("settings_search_empty_light", dark = false, query = "aardvark")
    }

    /**
     * Captures the screen once its state carries the query the test typed.
     *
     * The state is a combine that starts from an empty [SettingsUiState], and one of its
     * sources reads its store on the IO dispatcher. The compose rule does not wait for that
     * thread. On a slow run the capture came first, and showed the start state: no query, so
     * the full list. That failed `settings_search_light` on `main` on 2 November, on a commit
     * whose PR run had passed. `SettingsBehaviourTest.awaitText` waits for the same thing.
     *
     * The query is the condition because it is the field the start state gets wrong here.
     * Identity cannot be: `stateIn` drops a value equal to the one it holds, so a loaded state
     * that equals the start state never replaces it.
     */
    private fun capture(name: String, dark: Boolean, query: String = "") {
        viewModel.onQueryChange(query)
        compose.setContent {
            ScreenshotTheme(dark = dark) {
                SettingsScreen(
                    onBack = {},
                    onSignedOut = {},
                    onOpenLicenses = {},
                    viewModel = viewModel,
                )
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.state.value.query == query }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }
}

/**
 * lugu's palette without dynamic colour.
 *
 * The app prefers the wallpaper's colours from Android 12 onwards, which are by definition
 * not the same on two phones and so cannot be a baseline. These are the colours lugu falls
 * back to, which is what runs below Android 12 and wherever dynamic colour is off.
 */
@Composable
private fun ScreenshotTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) {
        darkColorScheme(primary = Color(0xFFEDC08B), secondary = Color(0xFFD6C3AE))
    } else {
        lightColorScheme(primary = Color(0xFF7A5A36), secondary = Color(0xFF6B5D4D))
    }
    MaterialTheme(colorScheme = colors) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}
