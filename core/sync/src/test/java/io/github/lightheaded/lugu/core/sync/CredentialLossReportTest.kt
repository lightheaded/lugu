package io.github.lightheaded.lugu.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The difference between "signed out" and "signed out, and here is why".
 *
 * Without this, a listener whose device replaced its encryption key meets a login screen
 * that looks like the app forgot them for no reason. With it, the screen can say what
 * happened and what to do.
 */
class CredentialLossReportTest {

    @Test
    fun `reports nothing in the normal case`() {
        assertThat(CredentialLossReport().lost.value).isEmpty()
    }

    @Test
    fun `names what is gone`() {
        val report = CredentialLossReport()
        report.record(CredentialKind.Tokens)
        assertThat(report.lost.value).containsExactly(CredentialKind.Tokens)
    }

    /**
     * Both files share one master key, so a replaced key ends both. The message has to name
     * both, because a certificate cannot be recalled from memory the way a password can.
     */
    @Test
    fun `gathers both stores`() {
        val report = CredentialLossReport()
        report.record(CredentialKind.Tokens)
        report.record(CredentialKind.ConnectionSettings)
        assertThat(report.lost.value)
            .containsExactly(CredentialKind.Tokens, CredentialKind.ConnectionSettings)
    }

    /** The same loss recorded twice is one loss, so nothing shows the message twice. */
    @Test
    fun `records one loss once`() {
        val report = CredentialLossReport()
        repeat(3) { report.record(CredentialKind.Tokens) }
        assertThat(report.lost.value).hasSize(1)
    }

    @Test
    fun `forgets the loss once it was shown`() {
        val report = CredentialLossReport()
        report.record(CredentialKind.Tokens)
        report.acknowledge()
        assertThat(report.lost.value).isEmpty()
    }
}
