package io.github.lightheaded.lugu

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * When the shell offers the sign-in for a lapse. The launch from a notification tap itself
 * is covered by the instrumented test; this pins the rule it runs on.
 */
class LapseOfferTest {

    /**
     * The review's case: a tap on the media notification opened the player, and the lapse
     * was read after. The offer must not cover the book that the tap asked for.
     */
    @Test
    fun `the offer waits while the player is on top`() {
        assertThat(lapseOffer(Routes.PLAYER, LAPSED, answeredServerId = null)).isEqualTo(LapseOffer.WAIT)
    }

    /** The player closed, and the library is on top again: now the offer comes. */
    @Test
    fun `the offer comes on the screen under the player`() {
        assertThat(lapseOffer(Routes.HOME, LAPSED, answeredServerId = null)).isEqualTo(LapseOffer.PUSH)
        assertThat(lapseOffer(Routes.ITEM, LAPSED, answeredServerId = null)).isEqualTo(LapseOffer.PUSH)
    }

    /** Once per lapse: a rotation keeps the answered id, and the screen is not pushed again. */
    @Test
    fun `an answered lapse is not offered again`() {
        assertThat(lapseOffer(Routes.HOME, LAPSED, answeredServerId = LAPSED)).isEqualTo(LapseOffer.NONE)
        assertThat(lapseOffer(Routes.PLAYER, LAPSED, answeredServerId = LAPSED)).isEqualTo(LapseOffer.NONE)
    }

    /** A second account that lapses later is a new lapse. */
    @Test
    fun `another account's lapse is offered`() {
        assertThat(lapseOffer(Routes.HOME, LAPSED, answeredServerId = "other#u2")).isEqualTo(LapseOffer.PUSH)
    }

    /** A sign-in screen already on top is the offer; a second one on it would need two Backs. */
    @Test
    fun `nothing is pushed over the sign-in screen`() {
        assertThat(lapseOffer(Routes.LOGIN, LAPSED, answeredServerId = null)).isEqualTo(LapseOffer.ALREADY_SHOWN)
    }

    /** A sign-out is not a lapse, so nothing is offered, wherever the listener is. */
    @Test
    fun `no lapse offers nothing`() {
        assertThat(lapseOffer(Routes.HOME, null, answeredServerId = null)).isEqualTo(LapseOffer.NONE)
        assertThat(lapseOffer(Routes.LOGIN, null, answeredServerId = LAPSED)).isEqualTo(LapseOffer.NONE)
    }

    /** At launch the graph can have no destination yet; the offer waits for the first one. */
    @Test
    fun `the offer waits for the first destination`() {
        assertThat(lapseOffer(null, LAPSED, answeredServerId = null)).isEqualTo(LapseOffer.WAIT)
    }

    /** The screen the lapse pushed offers "Not now" over whatever is under it, Accounts too. */
    @Test
    fun `the pushed offer has Not now`() {
        assertThat(offersNotNow(pushedForLapse = true, hasEntryUnder = true)).isTrue()
    }

    /** With nothing under it, "Not now" would close the app. */
    @Test
    fun `an offer with nothing under it has no Not now`() {
        assertThat(offersNotNow(pushedForLapse = true, hasEntryUnder = false)).isFalse()
    }

    /** "Add account" and "Sign in again" from Accounts are choices: Back is the way out. */
    @Test
    fun `a sign-in somebody chose has no Not now`() {
        assertThat(offersNotNow(pushedForLapse = false, hasEntryUnder = true)).isFalse()
    }

    @Test
    fun `the player opens over any screen but a sign-in`() {
        assertThat(playerMayOpenOver(Routes.HOME, topIsLapseOffer = false)).isTrue()
        assertThat(playerMayOpenOver(Routes.ACCOUNTS, topIsLapseOffer = false)).isTrue()
    }

    /** The tap asked for the book that plays through the lapse. */
    @Test
    fun `the player opens over the offer`() {
        assertThat(playerMayOpenOver(Routes.LOGIN, topIsLapseOffer = true)).isTrue()
    }

    /** Somebody adding an account is in the middle of something, even while another lapsed. */
    @Test
    fun `the player does not open over a sign-in somebody chose`() {
        assertThat(playerMayOpenOver(Routes.LOGIN, topIsLapseOffer = false)).isFalse()
    }

    /** The route string with its new argument still builds and keeps its old forms. */
    @Test
    fun `the offer argument is in the route only when set`() {
        assertThat(Routes.login()).isEqualTo("login")
        assertThat(Routes.login(offer = true)).isEqualTo("login?${Routes.LOGIN_OFFER}=true")
    }

    private companion object {
        const val LAPSED = "https://abs.example.com#u1"
    }
}
