package io.github.lightheaded.lugu.feature.library

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.api.AuthExpiredException
import io.github.lightheaded.lugu.core.api.AuthExpiredException.Reason
import org.junit.Test

/**
 * The status line after a sync that could not run, and the notice after an action that
 * could not. An ended session must not read as a library that is gone: the mirror and the
 * downloads are both still on the phone. And only an ended session may say so.
 */
class SyncFailureTextTest {

    @Test
    fun `an ended session says the downloads still play and where to sign in`() {
        val text = syncFailureText(AuthExpiredException("Not signed in", Reason.ENDED), "Could not reach the server")
        assertThat(text).isEqualTo(SESSION_ENDED_NOTE)
        assertThat(text).doesNotContain("Not signed in")
    }

    @Test
    fun `an action stopped by an ended session says the same, in words for a press`() {
        val text = actionFailureText(
            AuthExpiredException("Refresh rejected (401)", Reason.ENDED),
            "Could not start the download",
        )
        assertThat(text).isEqualTo(SESSION_ENDED_ACTION_NOTE)
        assertThat(text).contains("Downloads still play")
    }

    /**
     * A refresh that raced a switch or a sign-out, and a request with no account at all, are
     * not a lapse. Telling them that the session ended would send somebody to sign in to an
     * account that is fine.
     */
    @Test
    fun `an auth failure that is not a lapse keeps its own words`() {
        val race = AuthExpiredException("The sign-in changed during the refresh", Reason.CHANGED)
        val none = AuthExpiredException("No server configured", Reason.NO_ACCOUNT)

        assertThat(syncFailureText(race, "Could not reach the server")).isEqualTo(race.message)
        assertThat(syncFailureText(none, "Could not reach the server")).isEqualTo(none.message)
        assertThat(actionFailureText(race, "Could not start the download")).isEqualTo(race.message)
        assertThat(actionFailureText(none, "Could not start the download")).isEqualTo(none.message)
    }

    @Test
    fun `any other failure keeps its own words, or the fallback`() {
        assertThat(syncFailureText(IllegalStateException("Timed out"), "Could not reach the server"))
            .isEqualTo("Timed out")
        assertThat(syncFailureText(RuntimeException(), "Could not reach the server"))
            .isEqualTo("Could not reach the server")
        assertThat(actionFailureText(IllegalStateException("Needs 56 MB"), "Could not start the download"))
            .isEqualTo("Needs 56 MB")
        assertThat(actionFailureText(RuntimeException(), "Could not start the download"))
            .isEqualTo("Could not start the download")
    }
}
