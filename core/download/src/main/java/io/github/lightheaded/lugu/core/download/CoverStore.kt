package io.github.lightheaded.lugu.core.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lightheaded.lugu.core.api.AbsClient
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The cover of a downloaded item, kept as long as the download is.
 *
 * A download is a promise that the item works with no server. Audio kept that promise and
 * artwork did not: covers were only ever fetched on demand, through the app's authenticated
 * client, and cached where a cache may be dropped. So a fully downloaded book in a garage
 * with no signal showed a blank square — on the phone, and worse in the car, where the tile
 * *is* the way you pick a book at a glance.
 *
 * This is deliberately not another cache. It sits in `filesDir` rather than `cacheDir`
 * because the system may reclaim a cache directory at any moment, and the one moment that
 * matters here is the one where there is no network to re-fetch from. It is written when a
 * download is queued and removed when the last download for that item goes, so its lifetime
 * is the download's lifetime and nothing has to expire it.
 *
 * ## One file per item, not per width
 *
 * Callers ask for a cover at a width — 400 for a list row, 600 for a now-playing screen — and
 * get the same stored file whatever they ask. Storing several sizes of the same picture to
 * save a downscale would multiply the file count by the number of call sites for no visible
 * gain; [STORED_WIDTH] is the largest anything asks for, and scaling down from it is what
 * every image loader does anyway.
 *
 * ## Why this is not charged against the storage cap
 *
 * The cap governs [DownloadCache], and the readout beside it is that cache's own byte count —
 * the two agreeing is what makes a refusal explicable. A cover is tens of kilobytes, so a
 * phone holding four hundred of them is holding a few megabytes; adding that to a figure the
 * cap does not actually govern would put a second number next to the cap for the sake of
 * rounding error. Deliberate, and worth revisiting only if covers ever stop being small.
 */
@Singleton
class CoverStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val client: AbsClient,
    private val okHttpClient: OkHttpClient,
) {

    /**
     * One fetch at a time, because two of them can be for the same picture.
     *
     * Selecting eight episodes of a podcast queues eight downloads of one item, and every one
     * of them asks for that item's cover. Unserialised, they write the same temporary file at
     * once and the winner of the rename gets whatever the interleaving produced — a corrupt
     * image that then looks, to everything downstream, exactly like a stored one.
     *
     * A single lock rather than one per item: a cover is tens of kilobytes, so the cost of
     * doing them in turn is nothing worth the bookkeeping to avoid.
     */
    private val fetching = Mutex()

    /**
     * The stored cover, or null.
     *
     * Cheap enough to call from anywhere, including a binder thread serving the car: it is a
     * `stat`, not a read. Callers treat null as "ask the server", which is what they did
     * before this existed.
     */
    fun file(itemId: String): File? = coverFile(itemId)?.takeIf { it.isFile && it.length() > 0 }

    /**
     * Fetches and stores the cover, unless it is already here.
     *
     * Returns whether a cover is on disk afterwards rather than throwing, because every
     * caller wants the same thing from a failure: carry on. An item with no cover art at all
     * is an ordinary, permanent state — plenty of podcasts have none — and it must not read
     * as a download that went wrong.
     */
    suspend fun fetch(itemId: String): Boolean = withContext(Dispatchers.IO) {
        val target = coverFile(itemId) ?: return@withContext false
        if (target.isFile && target.length() > 0) return@withContext true
        fetching.withLock {
            // Re-checked inside the lock, which is what makes the common concurrent case free
            // rather than merely safe: queueing eight episodes of one podcast asks for the
            // same cover eight times, and seven of those now find it already here.
            if (target.isFile && target.length() > 0) return@withLock true
            runCatching { download(itemId, target) }.isSuccess
        }
    }

    /** Dropped when the last download for the item is. Silent: there may never have been one. */
    fun remove(itemId: String) {
        coverFile(itemId)?.delete()
    }

    /**
     * Drops every cover whose item no longer has a download.
     *
     * [remove] handles the ordinary case, but it is not the only way a download ends: signing
     * out deletes the account's rows outright, and so does anything that cascades from the
     * server row. Rather than trying to find every such path and hang a delete off it — the
     * kind of list that is complete until the next feature — this is checked once at startup
     * against the rows that actually exist. Anything left over is an orphan by definition.
     */
    suspend fun retainOnly(itemIds: Set<String>): Int = withContext(Dispatchers.IO) {
        val files = directory().listFiles().orEmpty()
        files.count { it.name !in itemIds && it.delete() }
    }

    /**
     * Written to a temporary file and moved into place, so a fetch cut off halfway cannot
     * leave a truncated image that every later call would then trust — the same reason the
     * download engine verifies byte counts rather than trusting a file's existence.
     */
    private suspend fun download(itemId: String, target: File) {
        val url = client.coverUrl(itemId, STORED_WIDTH)
        okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Cover request answered ${response.code}")
            val body = response.body ?: throw IOException("Cover request answered with nothing")
            target.parentFile?.mkdirs()
            val partial = File("${target.path}.part")
            partial.outputStream().use { out -> body.byteStream().copyTo(out) }
            if (partial.length() == 0L) {
                partial.delete()
                throw IOException("The server sent an empty cover")
            }
            if (!partial.renameTo(target)) {
                partial.delete()
                throw IOException("Could not store the cover")
            }
        }
    }

    private fun directory(): File = File(context.filesDir, DIRECTORY)

    /**
     * The file an id maps to, or null if the id is not one. See [CoverId].
     *
     * Returning null rather than throwing keeps a malformed id on the ordinary path: no
     * cover is stored for it, exactly as if none had ever been downloaded.
     */
    private fun coverFile(itemId: String): File? =
        if (CoverId.isValid(itemId)) File(directory(), itemId) else null

    companion object {
        private const val DIRECTORY = "downloaded-covers"

        /** The largest width anything asks for; everything else is a downscale of it. */
        const val STORED_WIDTH = 600
    }
}

/**
 * What counts as an item id, and where one is found in a URL.
 *
 * Its own object because both users of it are somewhere an id arrives from outside: the
 * exported `CoverProvider`, where another process wrote the `content://` URI, and image
 * loading, where the URL was built by a screen but the id has to be recovered from it. Pure,
 * so the awkward cases are settled in a unit test rather than on a phone.
 */
object CoverId {

    /**
     * Whether a string may be used as a cover's file name.
     *
     * The id becomes part of a path, so this is a check and not a comment: a segment of `..`
     * would otherwise name a file outside the directory it was meant to be in. A
     * server-generated id is letters, digits and hyphens, so anything else is rejected rather
     * than sanitised — there is no id it could have meant.
     */
    fun isValid(itemId: String): Boolean =
        itemId.isNotEmpty() && itemId.length <= MAX_LENGTH && itemId.all(::isIdCharacter)

    /**
     * The item id inside a cover URL, or null if that is not what the URL is.
     *
     * Exists so that image loading can ask "is this a cover, and whose?" without every screen
     * having to hand its item id down alongside the URL it already built. Matched on the shape
     * Audiobookshelf actually serves — `…/api/items/<id>/cover` — and deliberately strict:
     * anything else falls through to the network, which is what happened before it existed.
     */
    fun itemIdIn(url: String): String? {
        val start = url.indexOf(ITEMS_PATH).takeIf { it >= 0 } ?: return null
        val rest = url.substring(start + ITEMS_PATH.length)
        val id = rest.substringBefore('/', missingDelimiterValue = "")
        if (!isValid(id)) return null

        // The segment has to *be* `cover`, not merely begin with it: `/covers` is a different
        // endpoint, and treating it as this one would serve a book's artwork for something
        // else entirely.
        val after = rest.substring(id.length)
        if (!after.startsWith(COVER_PATH)) return null
        val next = after.getOrNull(COVER_PATH.length)
        return id.takeIf { next == null || next == '?' || next == '/' }
    }

    private fun isIdCharacter(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_'

    private const val ITEMS_PATH = "/api/items/"
    private const val COVER_PATH = "/cover"

    /** Generous next to a UUID, and short of anything a filesystem objects to. */
    private const val MAX_LENGTH = 128
}
