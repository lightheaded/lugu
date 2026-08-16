package io.github.lightheaded.lugu.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Every test here is really the same test asked from both ends: does a book that was cut off
 * come back, and does a book that was not cut off stay where it was put.
 *
 * The second half matters more. A phone that fails to resume is an annoyance somebody
 * notices and fixes with one press; a phone that starts a book out loud in a pocket an hour
 * later is the thing this project has said three times over that it will not do.
 */
class ReconnectPolicyTest {

    private val policy = ReconnectPolicy()

    private val now = 1_700_000_000_000L

    private fun stall(
        agoMs: Long = 0,
        networkFault: Boolean = true,
        wantedToPlay: Boolean = true,
    ) = NetworkStall(atMs = now - agoMs, networkFault = networkFault, wantedToPlay = wantedToPlay)

    private fun verdict(
        stall: NetworkStall?,
        hasSomethingLoaded: Boolean = true,
        alreadyPlaying: Boolean = false,
    ) = policy.verdictFor(stall, now, hasSomethingLoaded, alreadyPlaying)

    @Test
    fun `a book cut off by a tunnel carries on`() {
        val result = verdict(stall(agoMs = 2 * 60_000))

        assertThat(result.action).isEqualTo(ReconnectAction.RESUME)
    }

    /**
     * The whole point of clearing the stall on every transport command: with nothing
     * recorded there is nothing to undo, whatever else is true.
     */
    @Test
    fun `a book nobody was listening to stays where it is`() {
        val result = verdict(stall = null)

        assertThat(result.action).isEqualTo(ReconnectAction.WAIT)
        assertThat(result.reason).contains("nothing was stopped by the network")
    }

    /** A missing file and an expired token fail identically on the new connection. */
    @Test
    fun `a failure the network cannot explain is not retried by a network`() {
        val result = verdict(stall(networkFault = false))

        assertThat(result.action).isEqualTo(ReconnectAction.WAIT)
        assertThat(result.reason).contains("not the network's fault")
    }

    /**
     * An armed book whose first read failed is not an interrupted listening session, and
     * starting it would be the app taking the audio on its own.
     */
    @Test
    fun `a failure while paused is not a session to resume`() {
        val result = verdict(stall(wantedToPlay = false))

        assertThat(result.action).isEqualTo(ReconnectAction.WAIT)
        assertThat(result.reason).contains("nothing was playing")
    }

    @Test
    fun `nothing loaded means nothing to prepare`() {
        val result = verdict(stall(), hasSomethingLoaded = false)

        assertThat(result.action).isEqualTo(ReconnectAction.WAIT)
        assertThat(result.reason).contains("nothing is loaded")
    }

    /** Finding a book playing the next morning is not a feature. */
    @Test
    fun `an old stall is not carried on from`() {
        val result = verdict(stall(agoMs = ReconnectPolicy.WINDOW_MS + 1))

        assertThat(result.action).isEqualTo(ReconnectAction.WAIT)
        assertThat(result.reason).contains("past the window")
    }

    @Test
    fun `the edge of the window still counts as carrying on`() {
        val result = verdict(stall(agoMs = ReconnectPolicy.WINDOW_MS))

        assertThat(result.action).isEqualTo(ReconnectAction.RESUME)
    }

    /**
     * A clock moved backwards — by a time zone, an NTP correction, a reboot — must not make a
     * stall of any age look recent. The window is a guard, so it fails closed.
     */
    @Test
    fun `a stall from the future is refused rather than trusted`() {
        val result = verdict(stall(agoMs = -60_000))

        assertThat(result.action).isEqualTo(ReconnectAction.WAIT)
    }

    /**
     * Registering the callback delivers whatever network is already up, and a handover from
     * cell to Wi-Fi arrives mid-book. Neither should touch a book that is playing fine.
     */
    @Test
    fun `a network arriving mid-book does not interrupt it`() {
        val result = verdict(stall(), alreadyPlaying = true)

        assertThat(result.action).isEqualTo(ReconnectAction.WAIT)
        assertThat(result.reason).contains("already playing")
    }

    /** Every refusal has to be worth reading in the diary six hours later. */
    @Test
    fun `every verdict says why`() {
        val verdicts = listOf(
            verdict(null),
            verdict(stall(networkFault = false)),
            verdict(stall(wantedToPlay = false)),
            verdict(stall(), hasSomethingLoaded = false),
            verdict(stall(agoMs = ReconnectPolicy.WINDOW_MS * 2)),
            verdict(stall(), alreadyPlaying = true),
            verdict(stall()),
        )

        verdicts.forEach { assertThat(it.reason).isNotEmpty() }
    }
}
