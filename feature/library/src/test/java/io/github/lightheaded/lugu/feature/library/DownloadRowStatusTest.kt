package io.github.lightheaded.lugu.feature.library

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.db.DownloadState
import io.github.lightheaded.lugu.core.download.DownloadStatus
import org.junit.Test

/**
 * The row of the downloads list must be the same height in every state, and the words in
 * it are cut to make that true. Both halves are decided in text rather than in the
 * drawing, so both are pinned here.
 *
 * The failure texts below are the two kinds a row really gets: the app's own storage-cap
 * message, which is four sentences, and a Media3 exception message, which is one line of
 * jargon or nothing at all.
 */
class DownloadRowStatusTest {

    private val capMessage =
        "Stopped: downloads have reached the 8 GB cap, with 7.6 GB used. What has already " +
            "been fetched is kept. Raise the cap in Settings, or remove a download, then " +
            "start this again."

    private fun download(
        state: String,
        percent: Float = 0f,
        bytesDownloaded: Long = 0,
        error: String? = null,
    ) = DownloadStatus(
        libraryItemId = "li_1",
        episodeId = null,
        title = "Lighthouse Wakes",
        author = "James T. R. Corven",
        state = state,
        percent = percent,
        bytesDownloaded = bytesDownloaded,
        bytesTotal = 1_000,
        error = error,
    )

    @Test
    fun `a complete download reports what it costs`() {
        val status = rowStatusOf(download(DownloadState.COMPLETED, bytesDownloaded = 512))

        assertThat(status).isEqualTo(RowStatus.Size(512))
    }

    @Test
    fun `a download in flight reports how far it got`() {
        val queued = rowStatusOf(download(DownloadState.QUEUED))
        val running = rowStatusOf(download(DownloadState.DOWNLOADING, percent = 0.35f))

        assertThat(queued).isEqualTo(RowStatus.Progress(0f))
        assertThat(running).isEqualTo(RowStatus.Progress(0.35f))
    }

    /** A server can report anything. The bar cannot leave the row either way. */
    @Test
    fun `a percentage outside the bar is held inside it`() {
        assertThat(rowStatusOf(download(DownloadState.DOWNLOADING, percent = 1.4f)))
            .isEqualTo(RowStatus.Progress(1f))
        assertThat(rowStatusOf(download(DownloadState.DOWNLOADING, percent = -2f)))
            .isEqualTo(RowStatus.Progress(0f))
    }

    /**
     * The state decides, and a complete download is complete.
     *
     * The engine keeps the last error on the row after a retry succeeds, so a row can hold
     * both. A row that reports a failure over a file already on the phone is the worse of
     * the two mistakes.
     */
    @Test
    fun `a complete download says nothing about an old failure`() {
        val status = rowStatusOf(
            download(DownloadState.COMPLETED, bytesDownloaded = 200, error = capMessage),
        )

        assertThat(status).isEqualTo(RowStatus.Size(200))
    }

    @Test
    fun `a failure with no reason names the failure and the retry`() {
        val status = rowStatusOf(download(DownloadState.FAILED)) as RowStatus.Failure

        assertThat(status.line).isEqualTo("Failed. Tap the arrow to try again.")
        assertThat(status.full).isEqualTo(status.line)
        assertThat(status.hasMore).isFalse()
    }

    @Test
    fun `an empty reason counts as no reason`() {
        val status = rowStatusOf(download(DownloadState.FAILED, error = "   ")) as RowStatus.Failure

        assertThat(status.line).isEqualTo("Failed. Tap the arrow to try again.")
        assertThat(status.hasMore).isFalse()
    }

    /** One line of the four sentences, and the other three kept for the message channel. */
    @Test
    fun `the cap message gives the row its first clause and keeps the fix`() {
        val status = rowStatusOf(download(DownloadState.FAILED, error = capMessage))
            as RowStatus.Failure

        assertThat(status.line).isEqualTo("Stopped: downloads have reached the 8 GB cap…")
        assertThat(status.hasMore).isTrue()
        assertThat(status.full).contains("Raise the cap in Settings")
    }

    @Test
    fun `a reason short enough to fit is left alone`() {
        val status = rowStatusOf(download(DownloadState.FAILED, error = "Response code: 401"))
            as RowStatus.Failure

        assertThat(status.line).isEqualTo("Response code: 401")
        assertThat(status.full).isEqualTo("Response code: 401")
        assertThat(status.hasMore).isFalse()
    }

    /** Line breaks from an exception message must not make a row two lines tall. */
    @Test
    fun `every kind of white space becomes one space`() {
        val status = rowStatusOf(
            download(DownloadState.FAILED, error = "  Response code:\n  401\t "),
        ) as RowStatus.Failure

        assertThat(status.line).isEqualTo("Response code: 401")
        assertThat(status.hasMore).isFalse()
    }

    @Test
    fun `a short first sentence still marks the sentences it dropped`() {
        assertThat(shortenFailure("Not enough space. Free some and try again."))
            .isEqualTo("Not enough space…")
    }

    /** A figure in the middle of a sentence is not the end of it. */
    @Test
    fun `a decimal point does not end a sentence`() {
        assertThat(shortenFailure("7.6 GB is on the phone.")).isEqualTo("7.6 GB is on the phone.")
    }

    @Test
    fun `a long sentence is cut at a word`() {
        val cut = shortenFailure(
            "Unable to connect to the server at books.example after three tries",
        )

        assertThat(cut).isEqualTo("Unable to connect to the server at…")
    }

    /** An address has no space in it, so half an address is better than a tenth of one. */
    @Test
    fun `a single long word is cut where the line ends`() {
        val cut = shortenFailure("https://media.example/library/items/track-000000000001.m4b")

        assertThat(cut).isEqualTo("https://media.example/library/items/track-000000…")
    }

    @Test
    fun `a cut line is never longer than the line it was cut to`() {
        listOf(capMessage, "a".repeat(300), "word ".repeat(80)).forEach { long ->
            assertThat(shortenFailure(long).length).isAtMost(49)
        }
    }

    @Test
    fun `a short message holds the screen for the time a refusal needs`() {
        assertThat(readingTimeMs("Response code: 401")).isEqualTo(8_000L)
    }

    @Test
    fun `a long message holds the screen for longer`() {
        assertThat(readingTimeMs(capMessage)).isGreaterThan(8_000L)
        assertThat(readingTimeMs(capMessage)).isAtMost(24_000L)
    }

    @Test
    fun `no message holds the screen for half a minute`() {
        assertThat(readingTimeMs("word ".repeat(200))).isEqualTo(24_000L)
    }
}
