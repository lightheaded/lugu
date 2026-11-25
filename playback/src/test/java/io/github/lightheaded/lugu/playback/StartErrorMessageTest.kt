package io.github.lightheaded.lugu.playback

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.api.AuthExpiredException
import io.github.lightheaded.lugu.core.api.AuthExpiredException.Reason
import org.junit.Test

/**
 * What the player says when a start fails for want of a sign-in.
 *
 * Only a stream can fail this way: a downloaded item resolves from disk and never asks for
 * a token. So an ended session names itself and says that the downloads still play, rather
 * than "Sign in to play.", which read as if nothing could play at all. The cases are told
 * apart by [AuthExpiredException.reason], not by the words of the message.
 */
class StartErrorMessageTest {

    @Test
    fun `an ended session says that downloads still play`() {
        assertThat(startErrorMessage(AuthExpiredException("Not signed in", Reason.ENDED)))
            .isEqualTo(SESSION_ENDED_MESSAGE)
        assertThat(startErrorMessage(AuthExpiredException("Refresh rejected (401)", Reason.ENDED)))
            .isEqualTo(SESSION_ENDED_MESSAGE)
    }

    /**
     * `prepareStart` fails this way when no account is active, which a sign-out that lands
     * during a start can cause. There is no session to call ended, so the line only asks
     * for a sign-in.
     */
    @Test
    fun `no account at all asks for a sign-in and says nothing about a session`() {
        assertThat(startErrorMessage(AuthExpiredException("No account", Reason.NO_ACCOUNT)))
            .isEqualTo(NO_ACCOUNT_MESSAGE)
        assertThat(startErrorMessage(AuthExpiredException("No server configured", Reason.NO_ACCOUNT)))
            .isEqualTo(NO_ACCOUNT_MESSAGE)
    }

    /** A refresh that raced a switch or a sign-out is not a lapse. Another press works. */
    @Test
    fun `a sign-in that changed during a refresh is not called an ended session`() {
        val text = startErrorMessage(AuthExpiredException("The sign-in changed during the refresh", Reason.CHANGED))
        assertThat(text).isNotEqualTo(SESSION_ENDED_MESSAGE)
        assertThat(text).isNotEqualTo(NO_ACCOUNT_MESSAGE)
    }
}
