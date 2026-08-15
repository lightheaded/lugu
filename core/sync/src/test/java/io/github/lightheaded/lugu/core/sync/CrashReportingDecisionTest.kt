package io.github.lightheaded.lugu.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * lugu advertises that it collects nothing unless asked. These are the cases that
 * promise reduces to, so they are worth pinning rather than trusting to review.
 */
class CrashReportingDecisionTest {

    @Test
    fun `does not start without consent`() {
        assertThat(CrashReportingDecision.shouldStart(consentGiven = false, dsn = DSN)).isFalse()
    }

    @Test
    fun `starts once consent is given`() {
        assertThat(CrashReportingDecision.shouldStart(consentGiven = true, dsn = DSN)).isTrue()
    }

    /**
     * A build with no DSN — a fork, or a contributor's local release build — must not
     * report anywhere, no matter what the toggle says. Their crashes are not ours.
     */
    @Test
    fun `does not start without a dsn even with consent`() {
        assertThat(CrashReportingDecision.shouldStart(consentGiven = true, dsn = "")).isFalse()
        assertThat(CrashReportingDecision.shouldStart(consentGiven = true, dsn = "   ")).isFalse()
    }

    private companion object {
        const val DSN = "https://key@example.ingest.de.sentry.io/1"
    }
}
