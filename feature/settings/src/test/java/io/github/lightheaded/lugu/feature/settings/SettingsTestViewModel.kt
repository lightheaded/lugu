package io.github.lightheaded.lugu.feature.settings

import android.content.Context
import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.api.ConnectionRace
import io.github.lightheaded.lugu.core.api.DeviceInfoDto
import io.github.lightheaded.lugu.core.api.InMemoryTokenStore
import io.github.lightheaded.lugu.core.api.StaticServerUrlProvider
import io.github.lightheaded.lugu.core.db.LuguDatabase
import io.github.lightheaded.lugu.core.sync.ActiveServerUrlProvider
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.ConnectionPrefs
import io.github.lightheaded.lugu.core.sync.CrashReportingPrefs
import io.github.lightheaded.lugu.core.sync.DownloadPrefs
import io.github.lightheaded.lugu.core.sync.InMemoryAccountTokens
import io.github.lightheaded.lugu.core.sync.LibraryPrefs
import io.github.lightheaded.lugu.core.sync.PlaybackPrefs
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import io.github.lightheaded.lugu.core.sync.QueuePrefs
import io.github.lightheaded.lugu.core.sync.WallClock
import io.github.lightheaded.lugu.playback.CompanionDevices
import io.github.lightheaded.lugu.playback.PairedDevices

/**
 * The real [SettingsViewModel], built without Hilt for the Robolectric tests.
 *
 * Its dependencies all construct from a context, a Room database and a Ktor client that
 * never has to reach a server. Nothing here contacts anything: the client is pointed at
 * no server and holds no tokens.
 */
internal fun settingsViewModelForTest(context: Context, database: LuguDatabase): SettingsViewModel {
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
    return SettingsViewModel(
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
                // fail here rather than reach the network from a test.
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
        // is the point: the view model has to survive it, and the settings baselines are
        // the proof — the row appears in its ordinary wording rather than the screen going
        // blank. Take the guard out of `webClientReachable` and these pictures stop.
        connectionPrefs = ConnectionPrefs(context),
    )
}
