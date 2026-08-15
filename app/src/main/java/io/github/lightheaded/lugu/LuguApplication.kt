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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        SyncScheduler.schedulePeriodic(this)

        // Downloads outlive the app: a book can finish, fail or be cancelled by the
        // system while this process is dead. Without reconciling on start, the UI would
        // keep showing whatever was true when it was last alive.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { downloadEngine.reconcile() }
            // Reclaiming space for finished books is opt-in and off by default, so this
            // does nothing at all unless someone asked for it.
            runCatching {
                authRepository.account()?.let { downloadRepository.sweepFinished(it) }
            }
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
