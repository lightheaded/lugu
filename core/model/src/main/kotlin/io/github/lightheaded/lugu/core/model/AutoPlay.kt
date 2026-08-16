package io.github.lightheaded.lugu.core.model

/**
 * A device whose connecting is worth starting a book for.
 *
 * The [key] is the device's hardware address, tagged so that a stored record can be told
 * apart from anything else that ends up in the same preference. Two different mechanisms
 * notice a device arriving — a companion-device association on Android 12 and later, a
 * connection broadcast before that — and the address is the one thing both of them can name
 * a device by, which is why everything above this is written once rather than twice.
 *
 * The key never leaves the device. It is not written to the playback diary, which the
 * feedback screen can send, and nothing displays it: the [name] is what is shown, and the
 * name is what is recorded. An address identifies a piece of hardware a person carries
 * around, and it has no business in a bug report.
 *
 * The name is stored alongside rather than looked up when needed, because looking it up
 * again means asking the Bluetooth stack, and on Android 12 and later that needs a
 * permission this feature is deliberately built to avoid. A name gone stale because the
 * headphones were renamed is a cosmetic problem; a permission prompt to draw a settings row
 * is not.
 */
data class AutoPlayDevice(val key: String, val name: String)

/**
 * Starting a book when a chosen device connects.
 *
 * This is the rule set, with no Android in it: which connection counts, how long to wait,
 * and what has to still be true when the wait is over. What notices a device arriving, and
 * what actually presses play, live in the playback module.
 *
 * ## Why there is a wait at all
 *
 * A headset announces itself before it is ready to be played to. Between the connection and
 * the audio route actually moving there is a gap — a second or two on most devices, longer
 * on ones that chime at you first — and audio started inside that gap goes to the phone's
 * own speaker. The listener then hears the first sentence of their book out loud in a room,
 * or does not hear it at all. The wait is the fix, and it is a setting because the gap is a
 * property of the hardware rather than of Android.
 *
 * The wait is not the only guard: [decide] is asked again when it expires, and refuses if
 * the route never actually arrived. A device that connected and dropped straight out again
 * is common enough — a headset picked up, then put back down — and it must not leave a book
 * playing to nobody.
 */
object AutoPlay {

    /**
     * Long enough for the common case, short enough not to feel broken.
     *
     * Most headsets route within a second or two. Five leaves room for the slow ones without
     * anybody wondering whether the setting works.
     */
    const val DEFAULT_WAIT_SEC = 5

    /** Offered as one-tap choices; any value up to [MAX_WAIT_SEC] can still be stored. */
    val WAIT_CHOICES_SEC = listOf(0, 2, 5, 10, 15, 30)

    /**
     * Two minutes. Beyond this the connection has stopped being the reason playback started,
     * and something that begins a book two minutes after you put your headphones on is
     * indistinguishable from one that started on its own.
     */
    const val MAX_WAIT_SEC = 120

    /**
     * How long a cancelled start suppresses the next one, for the same device.
     *
     * Connecting a headset produces more than one event — the link comes up, then the audio
     * profile, sometimes then the call profile — and they arrive seconds apart. Without this,
     * pressing "Not now" would be undone by the next event in the same connection, which
     * reads as the cancel button not working.
     */
    const val CANCEL_SUPPRESSES_MS = 60_000L

    /**
     * The key for a device, from its hardware address.
     *
     * Case is normalised because the connection broadcast, the bonded device list and the
     * companion association do not all agree on it, and a device that matches on one screen
     * and not on another is indistinguishable from a broken feature.
     */
    fun deviceKey(address: String): String = "$ADDRESS_PREFIX${address.trim().uppercase()}"

    /** The address back out of a key, for the system calls that want one. */
    fun addressOf(key: String): String? =
        key.removePrefix(ADDRESS_PREFIX).takeIf { it != key && it.isNotBlank() }

    /** The chosen device this connection is, or null when it is not one of them. */
    fun match(devices: Collection<AutoPlayDevice>, key: String?): AutoPlayDevice? {
        if (key.isNullOrBlank()) return null
        return devices.firstOrNull { it.key == key }
    }

    /**
     * One device as a single line, for a store that holds strings.
     *
     * The separator is the ASCII unit separator, which cannot occur in a key and is stripped
     * out of a name on the way in — so a device called "Tom's — Buds" round-trips, and a
     * device whose name somehow contains a control character cannot corrupt the record next
     * to it.
     */
    fun encode(device: AutoPlayDevice): String =
        "${device.key}$SEPARATOR${device.name.replace(SEPARATOR, ' ').trim()}"

    /** One stored line back into a device, or null if it is not one. */
    fun decode(record: String): AutoPlayDevice? {
        val separator = record.indexOf(SEPARATOR)
        if (separator <= 0) return null
        val key = record.substring(0, separator)
        val name = record.substring(separator + 1).ifBlank { UNNAMED }
        if (!key.startsWith(ADDRESS_PREFIX)) return null
        return AutoPlayDevice(key, name)
    }

    /**
     * Whether a connection arriving now should be ignored because the last one was
     * cancelled.
     *
     * A null [cancelledAtMs] means nothing has been cancelled, so nothing is suppressed.
     */
    fun suppressedByCancel(nowMs: Long, cancelledAtMs: Long?): Boolean {
        if (cancelledAtMs == null) return false
        val since = nowMs - cancelledAtMs
        return since in 0 until CANCEL_SUPPRESSES_MS
    }

    /**
     * The last check, made when the wait is over rather than when the device connected.
     *
     * Everything here can change during the wait, and each of these has a way of being true
     * that has nothing to do with the listener wanting a book: a call can start, another app
     * can take the audio, the headset can drop out again. Refusing is always the safe answer,
     * because the cost of refusing is one press of play and the cost of not refusing is a
     * book talking over a phone call.
     */
    fun decide(conditions: AutoPlayConditions): AutoPlayOutcome = when {
        !conditions.deviceStillConnected -> AutoPlayOutcome.Refuse(AutoPlayRefusal.DEVICE_GONE)
        conditions.onACall -> AutoPlayOutcome.Refuse(AutoPlayRefusal.ON_A_CALL)
        conditions.someoneElseHasTheAudio ->
            AutoPlayOutcome.Refuse(AutoPlayRefusal.SOMETHING_ELSE_PLAYING)
        !conditions.hasSomethingToPlay -> AutoPlayOutcome.Refuse(AutoPlayRefusal.NOTHING_TO_PLAY)
        else -> AutoPlayOutcome.Start
    }

    /**
     * What a device is called when the system will not say.
     *
     * Android 12 hands back an association with no display name on it, and reading the real
     * one means a Bluetooth permission. Deliberately not a piece of the address instead: the
     * name is displayed and recorded, and half an address is still an address.
     */
    const val UNNAMED = "Bluetooth device"

    private const val ADDRESS_PREFIX = "address:"

    /** ASCII unit separator, the same one the playback diary uses and for the same reason. */
    private const val SEPARATOR = '\u001F'
}

/** What is true at the moment the wait ends. */
data class AutoPlayConditions(
    /** Whether an output that can be listened to is actually attached now. */
    val deviceStillConnected: Boolean,
    val someoneElseHasTheAudio: Boolean,
    val onACall: Boolean,
    /** Whether there is a last-played item to carry on with at all. */
    val hasSomethingToPlay: Boolean,
)

sealed interface AutoPlayOutcome {
    data object Start : AutoPlayOutcome

    data class Refuse(val refusal: AutoPlayRefusal) : AutoPlayOutcome
}

/**
 * Why a start did not happen, in words fit for the playback record.
 *
 * These are written down rather than dropped because the question this feature generates is
 * always "why didn't it play", and an empty record answers it with nothing.
 */
enum class AutoPlayRefusal(val reason: String) {
    DEVICE_GONE("the device had disconnected again"),
    ON_A_CALL("a call was in progress"),
    SOMETHING_ELSE_PLAYING("another app had the audio"),
    NOTHING_TO_PLAY("there was nothing to carry on with"),
}
