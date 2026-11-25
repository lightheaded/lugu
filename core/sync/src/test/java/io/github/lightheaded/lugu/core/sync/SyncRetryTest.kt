package io.github.lightheaded.lugu.core.sync

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.api.AuthExpiredException
import io.github.lightheaded.lugu.core.api.AuthExpiredException.Reason
import java.io.IOException
import org.junit.Test

/**
 * A session that ended is not a reason to wake the phone again.
 *
 * Every background pass asks [SyncRetry] before it hands a failure to WorkManager's
 * backoff. A missing token fails every request until the next sign-in, which runs the work
 * again by itself, so a retry in between only costs battery.
 */
class SyncRetryTest {

    @Test
    fun `a lapsed session is not retried`() {
        assertThat(SyncRetry.worthRetrying(AuthExpiredException("Not signed in", Reason.ENDED))).isFalse()
    }

    @Test
    fun `a refresh the server refuses is not retried`() {
        assertThat(SyncRetry.worthRetrying(AuthExpiredException("Refresh rejected (401)", Reason.ENDED))).isFalse()
    }

    @Test
    fun `no account is not retried`() {
        assertThat(SyncRetry.worthRetrying(AuthExpiredException("No server configured", Reason.NO_ACCOUNT))).isFalse()
    }

    @Test
    fun `a refused sign-in is not retried`() {
        assertThat(SyncRetry.worthRetrying(AuthExpiredException("Wrong username or password", Reason.REFUSED))).isFalse()
    }

    /** A race with a sign-out, a switch or a newer sign-in. The next attempt reads the new tokens. */
    @Test
    fun `a sign-in that changed during a refresh is retried`() {
        assertThat(
            SyncRetry.worthRetrying(AuthExpiredException("The sign-in changed during the refresh", Reason.CHANGED)),
        ).isTrue()
    }

    /** Every reason has a rule, and only the one that is a race retries. */
    @Test
    fun `only the race among the reasons is retried`() {
        val retried = Reason.entries.filter { SyncRetry.worthRetrying(AuthExpiredException("x", it)) }
        assertThat(retried).containsExactly(Reason.CHANGED)
    }

    @Test
    fun `a network failure is retried`() {
        assertThat(SyncRetry.worthRetrying(IOException("connection reset"))).isTrue()
    }

    @Test
    fun `any other failure is retried`() {
        assertThat(SyncRetry.worthRetrying(IllegalStateException("500"))).isTrue()
    }
}
