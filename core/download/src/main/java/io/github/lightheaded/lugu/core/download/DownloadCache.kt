package io.github.lightheaded.lugu.core.download

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
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
 * ## Two caches, and why they are not one
 *
 * Streamed audio is kept as well, but never here. It lives in a second [SimpleCache], in its
 * own directory, behind a least-recently-used evictor sized from the listener's setting, and
 * the separation is the whole point:
 *
 *  - **Nothing streamed can evict something downloaded.** A downloaded book is user-owned;
 *    streamed bytes are disposable. One evictor per cache is what makes that structural
 *    rather than a rule somebody has to remember. The bound on the retained cache can never
 *    reach into the download cache to free space, because it does not know it exists.
 *  - **Nothing streamed counts against the storage cap.** [bytesUsed] feeds the storage
 *    readout in settings and the cap check in `DownloadRepository`, and it means *downloads*.
 *    Retained streams are reported separately by [retainedStreamBytes], and the two must
 *    never be added together and shown as one figure — a listener who sees their downloads
 *    grow by 200 MB after a train journey during which they downloaded nothing has been told
 *    something false.
 *
 * The playback chain reads the download cache first, then the retained-stream cache, then the
 * network, and writes only to the middle one. See [playbackDataSourceFactory].
 */
@OptIn(UnstableApi::class)
@Singleton
class DownloadCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    val directory: File by lazy { File(context.filesDir, DIRECTORY).apply { mkdirs() } }

    private val streamDirectory: File by lazy { File(context.filesDir, STREAM_DIRECTORY) }

    /**
     * Shared by both caches, which is safe: a [SimpleCache] stamps its folder with a random
     * uid on first use and names its index tables after it, so two caches in two directories
     * keep two independent indexes inside one database file.
     */
    val databaseProvider: StandaloneDatabaseProvider by lazy { StandaloneDatabaseProvider(context) }

    /**
     * Built once, lazily, and warmed off the main thread.
     *
     * Constructing a [SimpleCache] indexes everything already on disk, which grows with
     * every downloaded book. The playback service builds its data source in `onCreate`,
     * on the main thread, so without warming this the first playback after a cold start
     * would block the main thread for as long as the index takes — an ANR waiting to
     * happen, and worst for the people who have downloaded the most. `lazy` is
     * thread-safe, so whichever gets there first does the work and the other waits.
     */
    val cache: SimpleCache by lazy {
        SimpleCache(directory, NoOpCacheEvictor(), databaseProvider)
    }

    private val streamLock = Any()

    /** Megabytes the current [streamCache] was sized for; zero means retention is off. */
    private var streamCacheMb = 0

    private var streamCache: SimpleCache? = null

    init {
        Thread({ runCatching { cache } }, "lugu-cache-warmup").apply { isDaemon = true }.start()
    }

    /** Upstream for the downloader: authenticated, and the only thing that fills the cache. */
    fun httpDataSourceFactory(): DataSource.Factory =
        DefaultDataSource.Factory(context, OkHttpDataSource.Factory(okHttpClient))

    /**
     * What the player reads through.
     *
     * Three layers, outermost first:
     *
     *  1. the download cache, **read-only** — `setCacheWriteDataSinkFactory(null)` is what
     *     makes that true, and it is the invariant the class documentation is about. A
     *     streamed book must never half-fill the download cache with fragments that then
     *     count against the storage cap and look like a download that is not one;
     *  2. the retained-stream cache, read *and* write, present only when the listener has
     *     asked for one;
     *  3. the network.
     *
     * The chaining is Media3's own mechanism and behaves as it reads: `CacheDataSource.Factory`
     * calls `upstreamDataSourceFactory.createDataSource()` for the source it consults on a
     * miss, so an inner `CacheDataSource` in that position is simply a smarter upstream. The
     * inner factory leaves its write sink unset, which is what gives it Media3's default
     * `CacheDataSink` against its own cache — that is the write, and it can only ever land in
     * the retained cache because that is the only cache the inner source was given.
     *
     * `FLAG_IGNORE_CACHE_ON_ERROR` on both: a cache that has gone wrong should degrade to a
     * plain network read rather than take playback down with it.
     *
     * ## When a setting change reaches this
     *
     * The retained cache is built once and kept for as long as the process holds it, because
     * a [SimpleCache] owns a lock on its folder and an index that has already been read. A
     * different [retainStreamedMb] is honoured by releasing the old cache and building a new
     * one, which is safe only because this method is called from the playback service's
     * `onCreate`, at a point where the previous player has been released and nothing is
     * reading. In practice that means: **changing how much streamed audio is kept takes
     * effect the next time the playback service starts.** Nothing here pretends otherwise.
     *
     * @param retainStreamedMb the listener's setting. Zero keeps nothing, and constructs no
     *   cache at all — no directory, no index, no folder lock. That is today's behaviour and
     *   it has to stay reachable, because it is the honest answer for a phone with no room.
     */
    fun playbackDataSourceFactory(retainStreamedMb: Int): DataSource.Factory {
        applyStreamRetention(retainStreamedMb)
        return CacheDataSource.Factory()
            .setCache(cache)
            // Resolved per data source rather than now, so the retained cache is indexed on
            // the loading thread that first needs it instead of on the main thread here.
            .setUpstreamDataSourceFactory(DataSource.Factory { retainingUpstream() })
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** Bytes currently held by **downloads**, for the storage readout and the cap check. */
    fun bytesUsed(): Long = runCatching { cache.cacheSpace }.getOrDefault(0L)

    /**
     * Bytes currently held by **retained streams**, which is a different number with a
     * different meaning: disposable, self-evicting, and not the listener's to manage.
     *
     * Zero when retention is off, and zero before anything has been streamed — the cache is
     * not built until the first read needs it, and asking how big it is must not be what
     * brings it into existence.
     */
    fun retainedStreamBytes(): Long =
        runCatching { synchronized(streamLock) { streamCache }?.cacheSpace }.getOrNull() ?: 0L

    private fun applyStreamRetention(retainStreamedMb: Int) {
        val wanted = retainStreamedMb.coerceAtLeast(0)
        synchronized(streamLock) {
            if (wanted == streamCacheMb) return
            streamCacheMb = wanted
            streamCache?.let { runCatching { it.release() } }
            streamCache = null
        }
    }

    /**
     * The layer between the download cache and the network: the retained-stream cache when
     * there is one, and the bare network when there is not.
     */
    private fun retainingUpstream(): DataSource {
        val retained = streamCache() ?: return httpDataSourceFactory().createDataSource()
        return CacheDataSource.Factory()
            .setCache(retained)
            .setUpstreamDataSourceFactory(httpDataSourceFactory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
    }

    /**
     * The retained-stream cache, built on first use.
     *
     * A failure to build one is not a failure to play: falling back to a plain network read
     * costs the listener the retention and nothing else, which is a far better outcome than
     * a book that will not start because a cache directory could not be created.
     */
    private fun streamCache(): SimpleCache? = synchronized(streamLock) {
        val megabytes = streamCacheMb
        if (megabytes <= 0) return null
        streamCache?.let { return it }
        streamDirectory.mkdirs()
        runCatching {
            SimpleCache(
                streamDirectory,
                LeastRecentlyUsedCacheEvictor(megabytes.toLong() * BYTES_PER_MB),
                databaseProvider,
            )
        }.getOrNull()?.also { streamCache = it }
    }

    private companion object {
        const val DIRECTORY = "downloads"

        /** Beside the downloads rather than inside them, so no sweep can confuse the two. */
        const val STREAM_DIRECTORY = "streamed"

        const val BYTES_PER_MB = 1024L * 1024
    }
}
