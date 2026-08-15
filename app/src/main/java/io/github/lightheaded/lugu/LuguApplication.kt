package io.github.lightheaded.lugu

import android.app.Application
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
import io.github.lightheaded.lugu.core.download.DownloadEngine
import io.github.lightheaded.lugu.core.download.DownloadRepository
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.CrashReportingPrefs
import io.github.lightheaded.lugu.core.sync.SyncScheduler
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@HiltAndroidApp
class LuguApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var okHttpClient: OkHttpClient

    @Inject lateinit var downloadEngine: DownloadEngine

    @Inject lateinit var downloadRepository: DownloadRepository

    @Inject lateinit var authRepository: AuthRepository

    @Inject lateinit var crashReporting: CrashReporting

    @Inject lateinit var crashReportingPrefs: CrashReportingPrefs

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()

        // First, and synchronously: a reporter started after the rest of startup misses
        // exactly the crashes that are hardest to reproduce. Reads a SharedPreference
        // rather than DataStore for the same reason — there is nothing to suspend on yet.
        crashReporting.applyConsent(crashReportingPrefs.isEnabled())

        SyncScheduler.schedulePeriodic(this)

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
        }

        // Turning the setting off has to stop the reporting there and then, not at the
        // next launch — withdrawing consent that keeps working for another session is
        // not consent. The settings screen only writes the flag; acting on it lives here
        // so the Sentry dependency stays inside :app.
        scope.launch {
            crashReportingPrefs.enabled.collect { crashReporting.applyConsent(it) }
        }
    }

    /**
     * Covers are served by the user's own server behind auth, so image loading shares
     * the app's OkHttp client — the same interceptor that tokens media requests.
     * No third-party image CDN is ever contacted.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient })) }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("covers"))
                    .maxSizeBytes(128L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
