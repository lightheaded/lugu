package io.github.lightheaded.lugu.core.download

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.db.DownloadState
import org.junit.Test

/**
 * The fold from per-file Media3 events into the one row a screen renders.
 *
 * No `DownloadManager` and no fake `DownloadIndex`: the index is read in [DownloadEngine]
 * and reduced to [FileProgress] there, which leaves the arithmetic — the part that has
 * actually been wrong — reachable from a plain unit test.
 */
@OptIn(UnstableApi::class)
class DownloadAggregationTest {

    private val unset = C.LENGTH_UNSET.toLong()

    private fun track(index: Int, durationSec: Double) = DownloadTrack(
        index = index,
        startOffsetSec = 0.0,
        durationSec = durationSec,
        url = "https://books.example/api/items/li_1/file/$index",
        mimeType = "audio/mp4",
        cacheKey = DownloadKeys.cacheKey("li_1", "", index),
    )

    @Test
    fun `a finished book reports its real byte total rather than the estimate`() {
        val files = listOf(
            track(1, 100.0) to FileProgress(Download.STATE_COMPLETED, 1_000, 1_000, 100f),
            track(2, 100.0) to FileProgress(Download.STATE_COMPLETED, 3_000, 3_000, 100f),
        )

        val folded = DownloadAggregation.fold(files, knownBytesTotal = 9_999)

        assertThat(folded.state).isEqualTo(DownloadState.COMPLETED)
        assertThat(folded.bytesTotal).isEqualTo(4_000)
        assertThat(folded.bytesDownloaded).isEqualTo(4_000)
        assertThat(folded.percent).isEqualTo(1f)
    }

    @Test
    fun `once every size is known the percentage is bytes over bytes`() {
        val files = listOf(
            track(1, 100.0) to FileProgress(Download.STATE_COMPLETED, 1_000, 1_000, 100f),
            track(2, 100.0) to FileProgress(Download.STATE_DOWNLOADING, 1_000, 3_000, 33.3f),
        )

        val folded = DownloadAggregation.fold(files, knownBytesTotal = 9_999)

        assertThat(folded.state).isEqualTo(DownloadState.DOWNLOADING)
        assertThat(folded.percent).isEqualTo(0.5f)
    }

    /**
     * The bug this fallback exists for. Media3 does not know a file's length until it has
     * opened it, so on a ten-file book nine lengths are unset for the first few seconds.
     * A naive bytes-over-total would read nought while the first file downloaded, and a
     * bar that does not move is indistinguishable from one that is stuck.
     */
    @Test
    fun `an unopened file does not drag the whole book to nought percent`() {
        val files = listOf(
            track(1, 100.0) to FileProgress(Download.STATE_DOWNLOADING, 500, 1_000, 50f),
            track(2, 100.0) to FileProgress(Download.STATE_QUEUED, 0, unset, 0f),
        )

        val folded = DownloadAggregation.fold(files, knownBytesTotal = 4_000)

        // Half of one of two equally long files.
        assertThat(folded.percent).isWithin(1e-4f).of(0.25f)
        // And the estimate stands in until every real length is in.
        assertThat(folded.bytesTotal).isEqualTo(4_000)
    }

    /** Weighted by duration, not by file count: a twenty-minute file is not a three-hour one. */
    @Test
    fun `the fallback percentage weights files by how long they are`() {
        val files = listOf(
            track(1, 60.0) to FileProgress(Download.STATE_COMPLETED, 100, unset, 100f),
            track(2, 540.0) to FileProgress(Download.STATE_QUEUED, 0, unset, 0f),
        )

        val folded = DownloadAggregation.fold(files, knownBytesTotal = 1_000)

        assertThat(folded.percent).isWithin(1e-4f).of(0.1f)
    }

    /** Media3 reports a negative percentage when it has nothing to base one on. */
    @Test
    fun `an unknown percentage counts as nothing rather than as a negative`() {
        val files = listOf(
            track(1, 100.0) to FileProgress(Download.STATE_DOWNLOADING, 0, unset, -1f),
            track(2, 100.0) to FileProgress(Download.STATE_COMPLETED, 100, unset, 100f),
        )

        val folded = DownloadAggregation.fold(files, knownBytesTotal = 1_000)

        assertThat(folded.percent).isWithin(1e-4f).of(0.5f)
    }

    @Test
    fun `one broken file fails the book even while the others are still going`() {
        val files = listOf(
            track(1, 100.0) to FileProgress(Download.STATE_DOWNLOADING, 500, 1_000, 50f),
            track(2, 100.0) to FileProgress(Download.STATE_FAILED, 0, unset, 0f),
        )

        assertThat(DownloadAggregation.fold(files, 2_000).state).isEqualTo(DownloadState.FAILED)
    }

    /**
     * A file the index has never heard of is not a file at nought per cent: the request
     * never took, or the bytes went away behind the app's back. Calling that "queued"
     * would leave the row waiting for an event that is never coming.
     */
    @Test
    fun `an item missing from the index entirely reads as failed`() {
        val files = listOf(track(1, 100.0) to null, track(2, 100.0) to null)

        val folded = DownloadAggregation.fold(files, knownBytesTotal = 2_000)

        assertThat(folded.state).isEqualTo(DownloadState.FAILED)
        assertThat(folded.percent).isEqualTo(0f)
        assertThat(folded.bytesTotal).isEqualTo(2_000)
    }

    /** One file gone and the rest complete is not a complete book. */
    @Test
    fun `a book missing one of its files is not reported as complete`() {
        val files = listOf(
            track(1, 100.0) to FileProgress(Download.STATE_COMPLETED, 1_000, 1_000, 100f),
            track(2, 100.0) to null,
        )

        assertThat(DownloadAggregation.fold(files, 2_000).state).isEqualTo(DownloadState.QUEUED)
    }

    @Test
    fun `a queued book reports itself queued and empty`() {
        val files = listOf(
            track(1, 100.0) to FileProgress(Download.STATE_QUEUED, 0, unset, 0f),
            track(2, 100.0) to FileProgress(Download.STATE_QUEUED, 0, unset, 0f),
        )

        val folded = DownloadAggregation.fold(files, knownBytesTotal = 2_000)

        assertThat(folded.state).isEqualTo(DownloadState.QUEUED)
        assertThat(folded.percent).isEqualTo(0f)
        assertThat(folded.bytesDownloaded).isEqualTo(0)
    }

    /**
     * A stopped file is one lugu stopped, and [StorageCap] is the only thing that ever does.
     * Read as queued it would leave the row promising to continue, waiting for a resumption
     * that will not come until the cap is raised — and hiding the message that says so.
     *
     * Waiting for Wi-Fi is a different state entirely: an unmet *requirement* leaves the file
     * queued, which is why that case is not caught here.
     */
    @Test
    fun `a download stopped at the storage cap reads as failed rather than queued`() {
        val files = listOf(
            track(1, 100.0) to FileProgress(Download.STATE_COMPLETED, 1_000, 1_000, 100f),
            track(2, 100.0) to FileProgress(Download.STATE_STOPPED, 400, 1_000, 40f),
        )

        val folded = DownloadAggregation.fold(files, knownBytesTotal = 2_000)

        assertThat(folded.state).isEqualTo(DownloadState.FAILED)
        // The bytes already fetched are still counted, because they are still on the disk.
        assertThat(folded.bytesDownloaded).isEqualTo(1_400)
    }

    /**
     * A podcast episode with no recorded duration would otherwise divide by nought, and a
     * NaN percentage renders as an empty bar for ever.
     */
    @Test
    fun `a track with no duration cannot produce a nonsense percentage`() {
        val files = listOf(track(1, 0.0) to FileProgress(Download.STATE_DOWNLOADING, 10, unset, 50f))

        val folded = DownloadAggregation.fold(files, knownBytesTotal = 100)

        assertThat(folded.percent).isEqualTo(0f)
    }

    /** Media3 can report more bytes than it expected; the bar still stops at the end. */
    @Test
    fun `the percentage never leaves the range a progress bar can draw`() {
        val files = listOf(track(1, 100.0) to FileProgress(Download.STATE_COMPLETED, 5_000, 1_000, 500f))

        assertThat(DownloadAggregation.fold(files, 1_000).percent).isEqualTo(1f)
    }
}
