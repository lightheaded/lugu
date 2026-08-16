package io.github.lightheaded.lugu.core.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The exception's message is not a log line. It is surfaced verbatim as a snackbar by
 * `ItemDetailViewModel`, so it is the whole of what the listener is told and is worth
 * asserting word by word.
 */
class DownloadRefusalTest {

    private fun message(refusal: DownloadRefusal) = DownloadRefusedException(refusal).message

    @Test
    fun `a transcode-only item is explained rather than blamed`() {
        val text = message(DownloadRefusal.TranscodeOnly(listOf("audio/x-ms-wma")))

        assertThat(text).isEqualTo(
            "The server would send this as a transcoded stream rather than as a file, " +
                "because its audio is WMA (audio/x-ms-wma) and this device has no " +
                "decoder for it. A transcode is made for one play session and expires " +
                "with it, so there is nothing stable to keep. Converting the file on " +
                "the server to a format this device decodes — MP3, M4B, FLAC, Opus or " +
                "WAV — is what would make it downloadable.",
        )
    }

    /**
     * "Failed" is what the Downloads screen says about a download that broke halfway.
     * Nothing was attempted here, so borrowing that word would send someone looking for
     * a network problem they do not have.
     */
    @Test
    fun `the refusal never claims something failed`() {
        val text = message(DownloadRefusal.TranscodeOnly(listOf("audio/x-caf"))).orEmpty()

        assertThat(text.lowercase()).doesNotContain("fail")
        assertThat(text.lowercase()).doesNotContain("error")
        assertThat(text.lowercase()).doesNotContain("unsupported")
    }

    /** All three parts are load-bearing: what the server does, why it cannot be kept, what changes it. */
    @Test
    fun `the refusal says what would change the answer`() {
        val text = message(DownloadRefusal.TranscodeOnly(listOf("audio/x-ms-wma"))).orEmpty()

        assertThat(text).contains("transcoded stream")
        assertThat(text).contains("expires")
        assertThat(text).contains("Converting the file on the server")
    }

    @Test
    fun `two offending formats read as a sentence`() {
        val text = message(DownloadRefusal.TranscodeOnly(listOf("audio/x-ms-wma", "audio/x-caf")))

        assertThat(text).contains("its audio is WMA (audio/x-ms-wma) and CAF (audio/x-caf) and this device")
    }

    @Test
    fun `three offending formats are listed rather than run together`() {
        val text = message(
            DownloadRefusal.TranscodeOnly(listOf("audio/x-ms-wma", "audio/x-caf", "audio/oddity")),
        )

        assertThat(text).contains("WMA (audio/x-ms-wma), CAF (audio/x-caf) and audio/oddity")
    }

    /** The standard the transcode refusal is held to: state the arithmetic, name the fix. */
    @Test
    fun `the storage cap refusal still states its arithmetic`() {
        val text = message(
            DownloadRefusal.OverStorageCap(
                usedBytes = 600L * 1024 * 1024,
                capBytes = 8L * 1024 * 1024 * 1024,
                neededBytes = 56L * 1024 * 1024,
            ),
        )

        // The gigabyte figure is formatted with a decimal separator the device chooses,
        // so it is asserted around rather than through.
        assertThat(text).startsWith("Needs 56 MB, and 600 MB of the ")
        assertThat(text).endsWith("GB cap is already used. Raise the cap in Settings, or remove a download.")
    }
}
