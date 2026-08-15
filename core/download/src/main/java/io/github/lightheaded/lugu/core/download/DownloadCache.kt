package io.github.lightheaded.lugu.core.download

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * Where downloaded audio lives, and the one factory that reads it back.
 *
 * The evictor is deliberately a no-op. The obvious choice, a least-recently-used
 * evictor, would quietly delete a book someone downloaded on purpose to make room for
 * one they merely streamed — the exact behaviour that makes an offline mode
 * untrustworthy. Space is instead bounded by refusing new downloads over the cap, which
 * is a decision the listener can see and act on.
 *
 * Streaming does not write here either ([playbackDataSourceFactory] has no write sink),
 * so the cache contains downloads and nothing else.
 */
@OptIn(UnstableApi::class)
@Singleton
class DownloadCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    val directory: File by lazy { File(context.filesDir, DIRECTORY).apply { mkdirs() } }

    val databaseProvider: StandaloneDatabaseProvider by lazy { StandaloneDatabaseProvider(context) }

    val cache: SimpleCache by lazy {
        SimpleCache(directory, NoOpCacheEvictor(), databaseProvider)
    }

    /** Upstream for the downloader: authenticated, and the only thing that fills the cache. */
    fun httpDataSourceFactory(): DataSource.Factory =
        DefaultDataSource.Factory(context, OkHttpDataSource.Factory(okHttpClient))

    /**
     * What the player reads through.
     *
     * `setCacheWriteDataSinkFactory(null)` makes playback read-only against the cache:
     * streaming a book must never half-fill it with fragments that then count against
     * the storage cap and look like a download that is not one.
     */
    fun playbackDataSourceFactory(): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory())
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Bytes currently held, for the storage readout in settings. */
    fun bytesUsed(): Long = runCatching { cache.cacheSpace }.getOrDefault(0L)

    private companion object {
        const val DIRECTORY = "downloads"
    }
}
