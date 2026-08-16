package io.github.lightheaded.lugu.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The device used throughout is invented, like every other example in this repo: a pair of
 * headphones called *Harbour Buds*, and an address that belongs to nothing.
 */
class AutoPlayTest {

    private val buds = AutoPlayDevice(AutoPlay.deviceKey("00:11:22:33:44:55"), "Harbour Buds")

    /** The ASCII unit separator a stored record is held together by. */
    private val separator = '\u001F'

    @Test
    fun `a key is the same however the address was written`() {
        assertThat(AutoPlay.deviceKey("aa:bb:cc:dd:ee:ff"))
            .isEqualTo(AutoPlay.deviceKey(" AA:BB:CC:DD:EE:FF "))
    }

    @Test
    fun `an address survives the round trip through a key`() {
        assertThat(AutoPlay.addressOf(AutoPlay.deviceKey("00:11:22:33:44:55")))
            .isEqualTo("00:11:22:33:44:55")
    }

    @Test
    fun `something that is not a key has no address in it`() {
        assertThat(AutoPlay.addressOf("companion:7")).isNull()
    }

    @Test
    fun `a device survives being stored and read back`() {
        assertThat(AutoPlay.decode(AutoPlay.encode(buds))).isEqualTo(buds)
    }

    /**
     * The separator is what holds a stored record together, so a name that contains one
     * would otherwise split the record in the wrong place and rename the device to whatever
     * came before it.
     */
    @Test
    fun `a name containing the separator cannot corrupt the record`() {
        val awkward = AutoPlayDevice(buds.key, "Harbour${separator}Buds")

        val decoded = AutoPlay.decode(AutoPlay.encode(awkward))

        assertThat(decoded).isEqualTo(AutoPlayDevice(buds.key, "Harbour Buds"))
    }

    @Test
    fun `a record that is not one decodes to nothing`() {
        assertThat(AutoPlay.decode("")).isNull()
        assertThat(AutoPlay.decode("Harbour Buds")).isNull()
        assertThat(AutoPlay.decode("${separator}Harbour Buds")).isNull()
        assertThat(AutoPlay.decode("something-else:00:11${separator}Harbour Buds")).isNull()
    }

    @Test
    fun `a device with no name left is still a device`() {
        val decoded = AutoPlay.decode("${buds.key}$separator   ")

        assertThat(decoded?.name).isEqualTo(AutoPlay.UNNAMED)
    }

    @Test
    fun `only the chosen devices match`() {
        assertThat(AutoPlay.match(listOf(buds), buds.key)).isEqualTo(buds)
        assertThat(AutoPlay.match(listOf(buds), AutoPlay.deviceKey("aa:aa:aa:aa:aa:aa"))).isNull()
        assertThat(AutoPlay.match(listOf(buds), null)).isNull()
        assertThat(AutoPlay.match(emptyList(), buds.key)).isNull()
    }

    @Test
    fun `nothing cancelled suppresses nothing`() {
        assertThat(AutoPlay.suppressedByCancel(nowMs = 10_000, cancelledAtMs = null)).isFalse()
    }

    /**
     * The case this exists for: the audio profile connecting a few seconds after the link
     * did, which without this would start the wait again immediately after it was refused.
     */
    @Test
    fun `a start cancelled a moment ago suppresses the next event from the same connection`() {
        assertThat(AutoPlay.suppressedByCancel(nowMs = 3_000, cancelledAtMs = 0)).isTrue()
    }

    @Test
    fun `a cancel stops suppressing once the connection is long over`() {
        val later = AutoPlay.CANCEL_SUPPRESSES_MS + 1
        assertThat(AutoPlay.suppressedByCancel(nowMs = later, cancelledAtMs = 0)).isFalse()
    }

    /**
     * A clock that has gone backwards — a time zone, a manual change, an NTP correction —
     * must not leave a cancel suppressing starts for the rest of the day.
     */
    @Test
    fun `a cancel from the future suppresses nothing`() {
        assertThat(AutoPlay.suppressedByCancel(nowMs = 0, cancelledAtMs = 60_000)).isFalse()
    }

    private fun conditions(
        audioSwitchedOver: Boolean = true,
        deviceStillConnected: Boolean = true,
        someoneElseHasTheAudio: Boolean = false,
        onACall: Boolean = false,
        hasSomethingToPlay: Boolean = true,
    ) = AutoPlayConditions(
        audioSwitchedOver = audioSwitchedOver,
        deviceStillConnected = deviceStillConnected,
        someoneElseHasTheAudio = someoneElseHasTheAudio,
        onACall = onACall,
        hasSomethingToPlay = hasSomethingToPlay,
    )

    @Test
    fun `all clear starts playing`() {
        assertThat(AutoPlay.decide(conditions())).isEqualTo(AutoPlayOutcome.Start)
    }

    /**
     * A watch, a keyboard, a fitness tracker: all of them connect, none of them is somewhere
     * to play a book. Told apart from a headset that disconnected because the two are
     * genuinely different and the record has to say which happened.
     */
    @Test
    fun `a device the audio never moved to is not played to`() {
        val outcome = AutoPlay.decide(
            conditions(audioSwitchedOver = false, deviceStillConnected = false),
        )

        assertThat(outcome).isEqualTo(AutoPlayOutcome.Refuse(AutoPlayRefusal.NO_AUDIO_ROUTE))
    }

    @Test
    fun `a device that dropped out again is not played to`() {
        val outcome = AutoPlay.decide(conditions(deviceStillConnected = false))

        assertThat(outcome).isEqualTo(AutoPlayOutcome.Refuse(AutoPlayRefusal.DEVICE_GONE))
    }

    @Test
    fun `a call is never played over`() {
        val outcome = AutoPlay.decide(conditions(onACall = true))

        assertThat(outcome).isEqualTo(AutoPlayOutcome.Refuse(AutoPlayRefusal.ON_A_CALL))
    }

    @Test
    fun `another app's audio is never taken`() {
        val outcome = AutoPlay.decide(conditions(someoneElseHasTheAudio = true))

        assertThat(outcome).isEqualTo(AutoPlayOutcome.Refuse(AutoPlayRefusal.SOMETHING_ELSE_PLAYING))
    }

    @Test
    fun `nothing listened to yet means nothing to carry on with`() {
        val outcome = AutoPlay.decide(conditions(hasSomethingToPlay = false))

        assertThat(outcome).isEqualTo(AutoPlayOutcome.Refuse(AutoPlayRefusal.NOTHING_TO_PLAY))
    }

    /**
     * The reason is displayed in the playback record, and a record that names the least
     * important of several reasons sends whoever reads it after the wrong thing.
     */
    @Test
    fun `the reason given is the most important one`() {
        val outcome = AutoPlay.decide(
            conditions(
                audioSwitchedOver = false,
                deviceStillConnected = false,
                onACall = true,
                someoneElseHasTheAudio = true,
                hasSomethingToPlay = false,
            ),
        )

        assertThat(outcome).isEqualTo(AutoPlayOutcome.Refuse(AutoPlayRefusal.NO_AUDIO_ROUTE))
    }

    /**
     * The audio switching over is waited for rather than guessed at, so the setting on top of
     * it is allowed to be nothing at all — which is what somebody who wants their book as
     * early as it can start will choose.
     */
    @Test
    fun `no extra wait is an offered choice`() {
        assertThat(AutoPlay.WAIT_CHOICES_SEC).contains(0)
    }

    @Test
    fun `the wait choices are all within what can be stored`() {
        assertThat(AutoPlay.WAIT_CHOICES_SEC.max()).isAtMost(AutoPlay.MAX_WAIT_SEC)
        assertThat(AutoPlay.WAIT_CHOICES_SEC).contains(AutoPlay.DEFAULT_WAIT_SEC)
    }
}
