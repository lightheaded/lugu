package io.github.lightheaded.lugu

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import io.github.lightheaded.lugu.core.download.CoverStore
import io.github.lightheaded.lugu.core.download.DownloadEngine
import io.github.lightheaded.lugu.core.download.DownloadRepository
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.CrashReportingPrefs
import io.github.lightheaded.lugu.core.download.DownloadScheduler
import io.github.lightheaded.lugu.core.sync.Realtime
import io.github.lightheaded.lugu.core.sync.RealtimeSync
import io.github.lightheaded.lugu.core.sync.SyncScheduler
import io.github.lightheaded.lugu.core.sync.PlaybackPrefs
import io.github.lightheaded.lugu.playback.CompanionDevices
import io.github.lightheaded.lugu.playback.PlaybackConnection
import io.github.lightheaded.lugu.playback.PlaybackStateHolder
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@HiltAndroidApp
class LuguApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var okHttpClient: OkHttpClient

    @Inject lateinit var downloadEngine: DownloadEngine

    @Inject lateinit var downloadRepository: DownloadRepository

    @Inject lateinit var coverStore: CoverStore

    @Inject lateinit var authRepository: AuthRepository

    @Inject lateinit var crashReporting: CrashReporting

    @Inject lateinit var crashReportingPrefs: CrashReportingPrefs

    @Inject lateinit var realtime: Realtime

    @Inject lateinit var realtimeSync: RealtimeSync

    @Inject lateinit var playbackStateHolder: PlaybackStateHolder

    /**
     * Held here only to arm the last-played item when the app comes to the foreground.
     * The connection is a singleton and reading the setting is its job, so nothing is
     * connected and no service is started unless somebody asked for that behaviour.
     */
    @Inject lateinit var playback: PlaybackConnection

    @Inject lateinit var playbackPrefs: PlaybackPrefs

    @Inject lateinit var companionDevices: CompanionDevices

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()

        // First, and synchronously: a reporter started after the rest of startup misses
        // exactly the crashes that are hardest to reproduce. Reads a SharedPreference
        // rather than DataStore for the same reason — there is nothing to suspend on yet.
        crashReporting.applyConsent(crashReportingPrefs.isEnabled())

        SyncScheduler.schedulePeriodic(this)

        // Podcast refreshes and the auto-download rules. Scheduled unconditionally: the
        // worker returns immediately when nothing is switched on, which is cheaper than
        // watching the settings to decide whether it should exist.
        DownloadScheduler.schedulePeriodic(this)

        // Downloads outlive the app: a book can finish, fail or be cancelled by the
        // system while this process is dead. Without reconciling on start, the UI would
        // keep showing whatever was true when it was last alive.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            runCatching { downloadEngine.reconcile() }
            // Reclaiming space for finished books is opt-in and off by default, so this
            // does nothing at all unless someone asked for it.
            runCatching {
                authRepository.account()?.let { downloadRepository.sweepFinished(it) }
            }
            // Cover files whose downloads no longer exist — the leftovers of a sign-out, or
            // of anything that removed rows without going through the repository.
            runCatching { downloadRepository.sweepCovers() }
        }

        // Turning the setting off has to stop the reporting there and then, not at the
        // next launch — withdrawing consent that keeps working for another session is
        // not consent. The settings screen only writes the flag; acting on it lives here
        // so the Sentry dependency stays inside :app.
        scope.launch {
            crashReportingPrefs.enabled.collect { crashReporting.applyConsent(it) }
        }

        // Asks the system, again, to be told when the chosen devices turn up. An
        // observation is a request this process made, and there is no guarantee it
        // survived whatever ended the last one — a restart, a system update, the app
        // being force stopped. Re-asking costs a few milliseconds and removes the
        // failure mode where the feature works until something invisible happens and
        // then never works again.
        scope.launch {
            runCatching {
                val devices = playbackPrefs.settings.first().autoPlay
                if (devices.armed) companionDevices.observe(devices.devices)
            }
        }

        // Live updates from the server. Everything they do is also done by the periodic
        // sync, so nothing here is allowed to matter: it exists to make an edit or a
        // position from another device arrive in seconds rather than hours.
        realtime.start(scope)
        realtimeSync.start(scope)

        // The socket is held while the app is on screen. ProcessLifecycleOwner is the
        // usual way to say that, but androidx.lifecycle:lifecycle-process is not a
        // declared dependency of this module, and a socket is not a good reason to add
        // one — counting started activities is the same answer from an API that is
        // already here. Realtime waits out a short grace period before disconnecting, so
        // a rotation or a glance at another app does not churn the connection.
        registerActivityLifecycleCallbacks(
            ForegroundWatcher { onScreen ->
                realtime.setForeground(onScreen)
                // Coming to the foreground is also when the last thing played is loaded
                // into the player, ready for a play press — but only if that was asked
                // for, and never playing on its own. The connection reads the setting
                // and does nothing at all under the other two, so this costs nothing to
                // call unconditionally.
                if (onScreen) playback.armLastPlayed()
            },
        )

        // And while something is loaded in the player, on screen or not: a position
        // changed on another device matters most in exactly that case. The same signal
        // tells RealtimeSync which item to leave alone, since progress for whatever is
        // playing may only move through the jump machinery a session start owns.
        scope.launch {
            playbackStateHolder.nowPlaying.collect { nowPlaying ->
                realtime.setPlaybackActive(nowPlaying != null)
                realtimeSync.setNowPlaying(nowPlaying?.libraryItemId, nowPlaying?.episodeId)
            }
        }
    }

    /**
     * Covers are served by the user's own server behind auth, so image loading shares
     * the app's OkHttp client — the same interceptor that tokens media requests.
     * No third-party image CDN is ever contacted.
     *
     * [DownloadedCoverInterceptor] sits in front of all of that, so a downloaded item shows
     * its cover with no server involved. It has to be an interceptor rather than a fetcher:
     * the screens pass a URL, and the substitution is a decision about *which source*, which
     * is the one thing a fetcher is chosen by rather than able to change.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(DownloadedCoverInterceptor(coverStore))
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("covers"))
                    .maxSizeBytes(128L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}

/**
 * Whether any activity is started, which is this app's definition of "on screen".
 *
 * Started rather than resumed: a paused-but-visible activity — a permission dialog on
 * top, or the app beside another in split screen — is still somebody looking at it.
 */
private class ForegroundWatcher(
    private val onChanged: (Boolean) -> Unit,
) : Application.ActivityLifecycleCallbacks {
    private var started = 0

    override fun onActivityStarted(activity: Activity) {
        started += 1
        if (started == 1) onChanged(true)
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) onChanged(false)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
