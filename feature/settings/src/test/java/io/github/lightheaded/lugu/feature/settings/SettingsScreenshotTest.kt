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
import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.api.ConnectionRace
import io.github.lightheaded.lugu.core.api.DeviceInfoDto
import io.github.lightheaded.lugu.core.api.InMemoryTokenStore
import io.github.lightheaded.lugu.core.api.StaticServerUrlProvider
import io.github.lightheaded.lugu.core.db.LuguDatabase
import io.github.lightheaded.lugu.core.sync.ActiveServerUrlProvider
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.InMemoryAccountTokens
import io.github.lightheaded.lugu.core.sync.ConnectionPrefs
import io.github.lightheaded.lugu.core.sync.CrashReportingPrefs
import io.github.lightheaded.lugu.core.sync.DownloadPrefs
import io.github.lightheaded.lugu.core.sync.LibraryPrefs
import io.github.lightheaded.lugu.core.sync.PlaybackPrefs
import io.github.lightheaded.lugu.playback.CompanionDevices
import io.github.lightheaded.lugu.playback.PairedDevices
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import io.github.lightheaded.lugu.core.sync.QueuePrefs
import io.github.lightheaded.lugu.core.sync.WallClock
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
 * The view model's six dependencies are all constructible without Hilt — four of them take
 * only a context, and the fifth and sixth are a Room database and a Ktor client that never
 * has to reach a server for this screen. Nothing here contacts anything: the client is
 * pointed at no server and holds no tokens.
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

        val client = AbsClient(
            serverUrlProvider = StaticServerUrlProvider(null),
            tokenStore = InMemoryTokenStore(),
            deviceInfo = DeviceInfoDto(deviceId = "screenshot"),
        )
        val progressRepository = ProgressRepository(
            client = client,
            progressDao = database.progressDao(),
            outboxDao = database.outboxDao(),
            positionHistoryDao = database.positionHistoryDao(),
            clock = WallClock(),
        )
        viewModel = SettingsViewModel(
            prefs = PlaybackPrefs(context),
            downloadPrefs = DownloadPrefs(context),
            authRepository = AuthRepository(
                client = client,
                serverDao = database.serverDao(),
                accountDataDao = database.accountDataDao(),
                tokenStore = InMemoryAccountTokens(),
                progressRepository = progressRepository,
                serverUrlProvider = ActiveServerUrlProvider(
                    serverDao = database.serverDao(),
                    connectionPrefs = ConnectionPrefs(context),
                    // The probe never runs: there is no active server, so nothing is
                    // raced. It refuses anyway, so a change that did start probing would
                    // fail here rather than reach the network from a screenshot test.
                    race = ConnectionRace(probe = { _, _ -> false }),
                ),
            ),
            crashReportingPrefs = CrashReportingPrefs(context),
            queuePrefs = QueuePrefs(context),
            libraryPrefs = LibraryPrefs(context),
            // Both answer from the platform, which under Robolectric reports no companion
            // device support and no Bluetooth feature — so the auto-play rows are absent
            // from the baseline, which is also what an emulator without Bluetooth shows.
            companionDevices = CompanionDevices(context),
            pairedDevices = PairedDevices(context),
            // Reading this opens the Android keystore, which Robolectric does not provide, so
            // it throws here exactly as it would on a device whose keystore is unusable. That
            // is the point: the view model has to survive it, and the baseline below is the
            // proof — the row appears in its ordinary wording rather than the screen going
            // blank. Take the guard out of `webClientReachable` and these pictures stop.
            connectionPrefs = ConnectionPrefs(context),
        )
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
        viewModel.onQueryChange("sleep")
        capture("settings_search_light", dark = false)
    }

    @Test
    fun `a search that matches nothing says so`() {
        viewModel.onQueryChange("aardvark")
        capture("settings_search_empty_light", dark = false)
    }

    private fun capture(name: String, dark: Boolean) {
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
