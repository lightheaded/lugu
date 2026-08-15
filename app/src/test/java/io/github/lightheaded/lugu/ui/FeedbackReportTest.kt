package io.github.lightheaded.lugu.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What the screen shows is what is sent, so these are tests of a promise rather than of a
 * string builder: anything the payload gains here is something a user has to be shown.
 */
class FeedbackReportTest {

    private val context = FeedbackContext(
        appVersion = "0.2.0-alpha01.7",
        deviceModel = "Google Pixel 7",
        androidVersion = "16 (SDK 37)",
        playbackActive = true,
        playerState = "playing",
    )

    @Test
    fun `the comment leads and the attachments are labelled`() {
        val report = FeedbackReport.compose("It stopped in the car.", context, null)

        assertThat(report).startsWith("It stopped in the car.")
        assertThat(report).contains("App version: 0.2.0-alpha01.7")
        assertThat(report).contains("Device: Google Pixel 7")
        assertThat(report).contains("Android: 16 (SDK 37)")
        assertThat(report).contains("Playback: active")
        assertThat(report).contains("Player: playing")
    }

    @Test
    fun `a run without a crash says so rather than leaving it out`() {
        val report = FeedbackReport.compose("Hello", context, null)

        assertThat(report).contains("no crash was recorded for the last run")
    }

    @Test
    fun `a crash it refers to is named`() {
        val report = FeedbackReport.compose("Hello", context.copy(crashEventId = "abc123"), null)

        assertThat(report).contains("Refers to the crash: abc123")
    }

    @Test
    fun `declining the playback record removes the section entirely`() {
        val withRecord = FeedbackReport.compose("Hello", context, "10:00:00  playing")
        val without = FeedbackReport.compose("Hello", context, null)

        assertThat(withRecord).contains("playback record")
        assertThat(withRecord).contains("10:00:00  playing")
        assertThat(without).doesNotContain("playback record")
    }

    @Test
    fun `a server address never leaves the phone`() {
        val report = FeedbackReport.compose(
            comment = "Failed against https://books.example.com/api/items?token=secret",
            context = context,
            playbackRecord = "10:00:00  player error — http://192.168.1.4:13378/stream.m4b",
        )

        assertThat(report).doesNotContain("books.example.com")
        assertThat(report).doesNotContain("secret")
        assertThat(report).doesNotContain("192.168.1.4")
        assertThat(report).contains("https://<server>")
        assertThat(report).contains("http://<server>")
    }

    @Test
    fun `the record is trimmed to its tail`() {
        val record = (1..100).joinToString("\n") { "line $it" }

        val tail = FeedbackReport.tailOf(record)

        assertThat(tail!!.lines()).hasSize(FeedbackReport.RECORD_TAIL_LINES)
        assertThat(tail.lines().last()).isEqualTo("line 100")
    }

    @Test
    fun `an empty record is no record`() {
        assertThat(FeedbackReport.tailOf("")).isNull()
        assertThat(FeedbackReport.tailOf("\n\n")).isNull()
    }
}
