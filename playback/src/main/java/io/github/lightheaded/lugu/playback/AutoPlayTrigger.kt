package io.github.lightheaded.lugu.playback

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.core.content.ContextCompat
import io.github.lightheaded.lugu.core.model.AutoPlay
import io.github.lightheaded.lugu.core.model.AutoPlayDevice
import io.github.lightheaded.lugu.core.sync.PlaybackDiary
import io.github.lightheaded.lugu.core.sync.PlaybackPrefs
import kotlinx.coroutines.flow.first

/**
 * The one path from "a chosen device just connected" to "start the last book".
 *
 * Two different things notice the connection — a companion-device service on Android 12 and
 * later, a connection broadcast before that — and they agree on nothing except the device
 * key. Everything they would otherwise each have to get right is here instead: whether the
 * feature is on, whether this is a device the listener picked, whether anything is already
 * playing, and what to do about it.
 *
 * ## Why this refuses before starting the service rather than after
 *
 * Starting the playback service commits to a foreground notification within a few seconds,
 * and if a book is already playing that notification would land on top of the one the player
 * owns. So the cheap questions are all asked here, where the answer can still be to do
 * nothing at all, and the service is only ever started when there is a real chance of it
 * playing something.
 *
 * `isMusicActive` covers both halves of "not now" in one call and needs no permission: it is
 * true whether the audio belongs to this app or to another one, and in both cases a device
 * connecting is not a reason to start a second thing playing.
 */
internal object AutoPlayTrigger {

    /**
     * When a start was last cancelled from the notification, so the events still arriving
     * from the same connection do not immediately undo it.
     *
     * Process-local on purpose. It exists to hold one connection's worth of events apart, and
     * a process that has died since has no pending start left to suppress.
     */
    @Volatile
    private var cancelledAtMs: Long? = null

    fun noteCancelled(atMs: Long) {
        cancelledAtMs = atMs
    }

    fun clearCancelled() {
        cancelledAtMs = null
    }

    /**
     * A device with this key has connected. Returns the device that was acted on, or null
     * when nothing happened — which is the common case, since most connections are to devices
     * nobody asked lugu to react to.
     */
    suspend fun onDeviceConnected(
        context: Context,
        key: String,
        prefs: PlaybackPrefs,
        diary: PlaybackDiary,
        nowMs: Long = System.currentTimeMillis(),
    ): AutoPlayDevice? {
        val settings = prefs.settings.first().autoPlay
        if (!settings.enabled) return null

        val device = AutoPlay.match(settings.devices, key) ?: return null

        if (AutoPlay.suppressedByCancel(nowMs, cancelledAtMs)) {
            diary.record(AUTO_PLAY_REFUSED, "${device.name}, a start had just been cancelled")
            return null
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager?.isMusicActive == true) {
            diary.record(AUTO_PLAY_REFUSED, "${device.name}, something was already playing")
            return null
        }

        diary.record(AUTO_PLAY_TRIGGERED, "${device.name}, waiting ${settings.waitSec}s")
        ContextCompat.startForegroundService(
            context,
            Intent(context, LuguPlaybackService::class.java).apply {
                action = ACTION_AUTO_PLAY
                putExtra(EXTRA_DEVICE_NAME, device.name)
                putExtra(EXTRA_WAIT_SEC, settings.waitSec)
            },
        )
        return device
    }

    /**
     * What the service is asked to do, named here rather than on the service because this is
     * the only thing that ever asks — an intent contract belongs with its sender.
     */
    const val ACTION_AUTO_PLAY = "io.github.lightheaded.lugu.AUTO_PLAY"
    const val ACTION_CANCEL_AUTO_PLAY = "io.github.lightheaded.lugu.CANCEL_AUTO_PLAY"
    const val EXTRA_DEVICE_NAME = "device_name"
    const val EXTRA_WAIT_SEC = "wait_sec"

    /**
     * Recorded under names of their own rather than the shared playback ones, because the
     * question these answer — "why did my book not start when I put my headphones on" — is
     * asked of this feature specifically, and a record that says only "paused" does not
     * answer it.
     */
    const val AUTO_PLAY_TRIGGERED = "auto-play triggered"
    const val AUTO_PLAY_REFUSED = "auto-play refused"
    const val AUTO_PLAY_STARTED = "auto-play started playback"
    const val AUTO_PLAY_CANCELLED = "auto-play cancelled by the listener"
}
