package io.github.lightheaded.lugu.core.download

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import io.github.lightheaded.lugu.core.db.DownloadState

/**
 * One file as the Media3 download index reports it, reduced to the four fields the fold
 * reads.
 *
 * This exists so the arithmetic below can be tested. Media3's own `Download` is only
 * obtainable from a real `DownloadManager`, which needs a cache directory, a database and
 * a thread pool; reducing it to a value class at the boundary keeps the part that can be
 * wrong — the fold — reachable from a plain unit test.
 */
@OptIn(UnstableApi::class)
internal data class FileProgress(
    /** A Media3 `Download.STATE_*`. */
    val state: Int,
    val bytesDownloaded: Long = 0,
    /** [C.LENGTH_UNSET] until the server's first response header has been read. */
    val contentLength: Long = C.LENGTH_UNSET.toLong(),
    /** Nought to a hundred, or negative while Media3 has nothing to base it on. */
    val percentDownloaded: Float = 0f,
)

/** The single row a screen renders for one item, whatever it took to get there. */
internal data class ItemProgress(
    val state: String,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val percent: Float,
)

/**
 * Media3 counts files; a listener counts books. This is the translation.
 */
@OptIn(UnstableApi::class)
internal object DownloadAggregation {
    /**
     * Folds every file of one item into one row.
     *
     * A null [FileProgress] means the index has never heard of that file, which is not
     * the same as it being at nought: it is a request that was never accepted, or bytes
     * removed behind the app's back.
     *
     * @param knownBytesTotal the row's existing total, kept while any file's size is
     *   still unknown so the readout does not swing between an estimate and a partial sum.
     */
    fun fold(files: List<Pair<DownloadTrack, FileProgress?>>, knownBytesTotal: Long): ItemProgress {
        val bytesDownloaded = files.sumOf { (_, file) -> file?.bytesDownloaded ?: 0L }
        val contentLengths = files.map { (_, file) -> file?.contentLength ?: C.LENGTH_UNSET.toLong() }
        val allSizesKnown = contentLengths.all { it > 0 }
        val bytesTotal = if (allSizesKnown) contentLengths.sum() else knownBytesTotal

        // Falling back to a duration-weighted average matters for the first seconds of a
        // multi-file book, when most files have not been opened and their sizes are still
        // unknown — a naive bytes/total would read 0% for a while and look stuck.
        val percent = if (bytesTotal > 0 && allSizesKnown) {
            (bytesDownloaded.toDouble() / bytesTotal).toFloat()
        } else {
            val weight = files.sumOf { (track, _) -> track.durationSec }.takeIf { it > 0 } ?: 1.0
            files.sumOf { (track, file) ->
                val fraction = file?.percentDownloaded?.takeIf { it >= 0f }?.div(100f) ?: 0f
                track.durationSec * fraction
            }.div(weight).toFloat()
        }.coerceIn(0f, 1f)

        val states = files.map { (_, file) -> file?.state }
        val state = when {
            states.all { it == Download.STATE_COMPLETED } -> DownloadState.COMPLETED
            states.any { it == Download.STATE_FAILED } -> DownloadState.FAILED
            states.any { it == Download.STATE_DOWNLOADING } -> DownloadState.DOWNLOADING
            // Nothing at all in the index, for any file. The requests never took, and
            // reporting that as "queued" would leave the row waiting for an event that
            // is never coming.
            states.all { it == null } -> DownloadState.FAILED
            else -> DownloadState.QUEUED
        }

        return ItemProgress(
            state = state,
            bytesDownloaded = bytesDownloaded,
            bytesTotal = bytesTotal,
            percent = percent,
        )
    }
}
