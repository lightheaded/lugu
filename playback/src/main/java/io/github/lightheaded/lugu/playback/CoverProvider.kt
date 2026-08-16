package io.github.lightheaded.lugu.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.download.CoverId
import io.github.lightheaded.lugu.core.download.CoverStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Cover images, for the processes that cannot ask the server for them.
 *
 * Covers live behind the listener's own authentication: `/api/items/:id/cover` answers 401
 * without lugu's token, and the token is attached by an OkHttp interceptor inside this app.
 * That is invisible from the inside — the app's own screens load covers through the same
 * client and they appear — and total from the outside.
 *
 * **Android Auto fetches artwork in its own process.** Handed an `https://` URL, it makes an
 * anonymous request, is refused, and shows a blank tile. Every row in the car, and the cover
 * on the car's now-playing screen, was empty for exactly this reason. Nothing was logged and
 * nothing failed, because from lugu's side nothing happened at all.
 *
 * So the artwork a browse tree hands out is a `content://` URI pointing here instead. Reading
 * it comes back into this process, where the token is, and the bytes are fetched with the same
 * authenticated client as everything else. What crosses the process boundary is a picture.
 *
 * ## Why it is exported
 *
 * A `content://` URI is only readable by another app if this provider is exported, and there
 * is no way to hand Android Auto a permission grant through the media browser API — browse
 * results carry no URI grants. So it is open, and what that opens is worth stating plainly: an
 * app that already knows an Audiobookshelf item id can fetch that item's cover art through
 * lugu. Item ids are server-generated and unguessable, [query] deliberately enumerates
 * nothing, and no token, address or account detail is reachable through any path here. The
 * thing exposed is a book cover.
 *
 * ## Where the bytes come from
 *
 * Three places, in order. A downloaded item has its cover stored beside its audio by
 * [CoverStore], and that is answered first and without a network in sight — it is what makes
 * a fully downloaded book look like itself in a garage with no signal. Failing that, the
 * cache here, which keeps whatever a browse session has already looked at. Failing that, the
 * server.
 *
 * So the remaining gap is narrow and worth naming: an item that was never downloaded and
 * never looked at, on a phone with no connection, still shows a blank tile and has to be
 * recognised by its title.
 */
class CoverProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /**
     * Deliberately vague. Audiobookshelf serves whatever the cover was uploaded as — jpeg,
     * png, webp — and claiming one of them would be a guess that a decoder might believe.
     */
    override fun getType(uri: Uri): String = "image/*"

    /**
     * Enough for a client that asks what it is about to open, and nothing more.
     *
     * No listing, no ids, no directory: a query for a cover answers about that cover only. The
     * size is left null rather than fetched, because answering it would mean downloading the
     * image to reply to a question asked before the download.
     */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val itemId = itemIdOf(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> "$itemId.img"
                        else -> null
                    }
                }.toTypedArray(),
            )
        }
    }

    /**
     * The cover itself, from the cache or from the server.
     *
     * Blocking is correct here: `openFile` is called on a binder thread and is expected to
     * take as long as opening a file takes. A failure returns null rather than throwing, which
     * the caller renders as the blank tile it would have shown anyway — a car that cannot load
     * a picture must still show the row.
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw java.lang.SecurityException("Covers are read-only")
        val context = context ?: return null
        val itemId = itemIdOf(uri) ?: return null
        val width = uri.getQueryParameter(WIDTH)?.toIntOrNull() ?: DEFAULT_WIDTH

        // A downloaded item answers from its own stored cover, before anything considers the
        // network. This is the case the cache below could never cover: a car in a garage with
        // no signal, opening a book that is entirely on the phone. Served at whatever width it
        // was stored at, because a downscale is invisible and a missing picture is not.
        downloadedCover(context, itemId)?.let {
            return ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        val cached = cacheFile(context, itemId, width)
        if (!cached.isFresh()) {
            runCatching { download(context, itemId, width, cached) }.getOrElse { return null }
        }
        if (!cached.isFile || cached.length() == 0L) return null
        return ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * Wrapped because this runs before Hilt is guaranteed to have anything: a provider is
     * created early in process start, and a car asking for artwork is one of the things that
     * can start the process. A failure here is the same as no download — fall through.
     */
    private fun downloadedCover(context: Context, itemId: String): File? = runCatching {
        EntryPointAccessors.fromApplication(context.applicationContext, CoverDependencies::class.java)
            .coverStore()
            .file(itemId)
    }.getOrNull()

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Covers come from the server")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Covers come from the server")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Covers come from the server")

    /**
     * Fetches through the app's own client, so the auth header goes on exactly as it does for
     * every other request. Written to a temporary file and moved into place, so a fetch cut
     * off halfway cannot leave a truncated image that the cache would then trust.
     */
    private fun download(context: Context, itemId: String, width: Int, target: File) {
        val dependencies = EntryPointAccessors.fromApplication(
            context.applicationContext,
            CoverDependencies::class.java,
        )
        val url = runBlocking { dependencies.absClient().coverUrl(itemId, width) }
        val request = Request.Builder().url(url).build()
        dependencies.okHttpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Cover request answered ${response.code}")
            val body = response.body ?: throw IOException("Cover request answered with nothing")
            target.parentFile?.mkdirs()
            val partial = File("${target.path}.part")
            partial.outputStream().use { out -> body.byteStream().copyTo(out) }
            if (!partial.renameTo(target)) {
                partial.delete()
                throw IOException("Could not store the cover")
            }
        }
        prune(target.parentFile)
    }

    /**
     * Keeps the cache to a size worth having.
     *
     * Covers are small and a library is not, so this is a count rather than a byte budget:
     * enough for everything a car session touches, trimmed oldest-first when it is not.
     */
    private fun prune(directory: File?) {
        val files = directory?.listFiles()?.takeIf { it.size > MAX_CACHED_COVERS } ?: return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_CACHED_COVERS)
            .forEach { it.delete() }
    }

    private fun cacheFile(context: Context, itemId: String, width: Int): File =
        File(File(context.cacheDir, CACHE_DIRECTORY), "${itemId}_$width")

    private fun File.isFresh(): Boolean =
        isFile && length() > 0 && System.currentTimeMillis() - lastModified() < CACHE_TTL_MS

    /**
     * The id out of `content://…/cover/<itemId>`, or null for anything else.
     *
     * The id becomes part of a file name, and this provider is exported, so what arrives here
     * is whatever another process chose to write — see [CoverId.isValid]. Without that check,
     * a segment of `..` names a file outside the cache directory.
     */
    private fun itemIdOf(uri: Uri): String? {
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != COVER_PATH) return null
        return segments[1].takeIf(CoverId::isValid)
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface CoverDependencies {
        fun okHttpClient(): OkHttpClient

        fun absClient(): AbsClient

        fun coverStore(): CoverStore
    }

    companion object {
        private const val COVER_PATH = "cover"
        private const val WIDTH = "width"
        private const val DEFAULT_WIDTH = 400
        private const val CACHE_DIRECTORY = "auto-covers"
        private const val MAX_CACHED_COVERS = 400

        /**
         * A cover changes when somebody edits the item, which is rare, and a stale one is a
         * cosmetic problem for a day. Re-fetching more eagerly would mean a car with no signal
         * losing pictures it already had.
         */
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

        /**
         * The address of a cover, for the metadata that leaves this process.
         *
         * The authority carries the application id, so a debug build and a release build
         * installed side by side serve their own covers rather than fighting over one
         * authority — the same reason the automation actions are namespaced.
         */
        fun uri(context: Context, itemId: String, width: Int = DEFAULT_WIDTH): Uri =
            Uri.Builder()
                .scheme("content")
                .authority("${context.packageName}.covers")
                .appendPath(COVER_PATH)
                .appendPath(itemId)
                .appendQueryParameter(WIDTH, width.toString())
                .build()
    }
}
