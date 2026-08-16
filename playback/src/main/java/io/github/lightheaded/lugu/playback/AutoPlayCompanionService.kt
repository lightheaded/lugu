package io.github.lightheaded.lugu.playback

import android.companion.CompanionDeviceService
import android.os.Build
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint
import io.github.lightheaded.lugu.core.model.AutoPlay
import io.github.lightheaded.lugu.core.sync.PlaybackDiary
import io.github.lightheaded.lugu.core.sync.PlaybackPrefs
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The system telling lugu that a chosen device has turned up.
 *
 * Bound by the platform, not by this app: the association made in [CompanionDevices] is what
 * gives the system a reason to start this process at all, which is the whole point — the app
 * may have been closed for days, and this still runs.
 *
 * Doing nothing here is the normal outcome. Whether a book actually starts is
 * [AutoPlayTrigger]'s decision, and most of the reasons not to — the feature switched off, a
 * device that is no longer in the list, something already playing — are answered without ever
 * touching the player.
 */
@RequiresApi(Build.VERSION_CODES.S)
@AndroidEntryPoint
class AutoPlayCompanionService : CompanionDeviceService() {

    @Inject lateinit var playbackPrefs: PlaybackPrefs

    @Inject lateinit var diary: PlaybackDiary

    /**
     * Outlives the callback, which returns immediately. Reading the settings suspends, and a
     * binding the system is free to tear down must not be what the work is anchored to.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Superseded by `onDeviceEvent` in Android 16, whose default implementation forwards
     * connect and disconnect events to this method and its pair. Overriding the older name
     * keeps one implementation across every version that has this class at all.
     */
    @Deprecated("Superseded by onDeviceEvent, which forwards to it")
    override fun onDeviceAppeared(address: String) {
        val key = AutoPlay.deviceKey(address)
        scope.launch {
            runCatching {
                AutoPlayTrigger.onDeviceConnected(
                    context = applicationContext,
                    key = key,
                    prefs = playbackPrefs,
                    diary = diary,
                )
            }
        }
    }

    @Deprecated("Superseded by onDeviceEvent, which forwards to it")
    override fun onDeviceDisappeared(address: String) {
        // Nothing. A device going away is already handled where it matters: Media3 pauses on
        // the audio route becoming noisy, and the settings decide whether it should.
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
