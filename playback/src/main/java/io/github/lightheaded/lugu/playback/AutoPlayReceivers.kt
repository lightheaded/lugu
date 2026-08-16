package io.github.lightheaded.lugu.playback

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.IntentCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.lightheaded.lugu.core.model.AutoPlay
import io.github.lightheaded.lugu.core.sync.PlaybackDiary
import io.github.lightheaded.lugu.core.sync.PlaybackPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What both receivers here need from the graph.
 *
 * Read through an entry point rather than with `@AndroidEntryPoint`, for the reason
 * `AutomationReceiver` gives: Hilt's receiver support requires calling `super.onReceive`, and
 * that member is abstract on `BroadcastReceiver`, which Kotlin refuses to compile.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface AutoPlayDependencies {
    fun playbackPrefs(): PlaybackPrefs

    fun diary(): PlaybackDiary

    fun companionDevices(): CompanionDevices
}

private fun dependencies(context: Context): AutoPlayDependencies =
    EntryPointAccessors.fromApplication(context.applicationContext, AutoPlayDependencies::class.java)

/**
 * A device connecting, on the versions of Android where a broadcast is still the way to
 * hear about it.
 *
 * Android 11 and earlier only. From Android 12 this is both unnecessary and unusable:
 * [CompanionDevices] explains why at length, but in short, reading which device connected
 * needs a runtime Bluetooth permission and starting playback from the background needs an
 * exemption that a Bluetooth broadcast does not grant. Neither restriction exists here, and
 * `BLUETOOTH` is a normal permission on these versions, so the straightforward implementation
 * is also the correct one.
 *
 * The broadcast is one of the handful exempt from Android 8's implicit-broadcast restrictions,
 * which is what allows it to be declared in the manifest and to start a dead process.
 */
class BluetoothConnectionReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) return
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return

        val device = IntentCompat.getParcelableExtra(
            intent,
            BluetoothDevice.EXTRA_DEVICE,
            BluetoothDevice::class.java,
        ) ?: return

        // An exception out of a receiver takes the process with it, and this one runs on
        // somebody else's schedule. A device that will not say its address must not be able
        // to end a book that is playing.
        val address = runCatching { device.address }.getOrNull() ?: return

        val pending = goAsync()
        scope.launch {
            try {
                val graph = dependencies(context)
                withTimeoutOrNull(SETTINGS_TIMEOUT_MS) {
                    AutoPlayTrigger.onDeviceConnected(
                        context = context.applicationContext,
                        key = AutoPlay.deviceKey(address),
                        prefs = graph.playbackPrefs(),
                        diary = graph.diary(),
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /**
         * Short, because the broadcast is held open across it and Android kills a receiver
         * that takes more than ten seconds. A settings store that has not answered in two is
         * not going to answer in nine.
         */
        const val SETTINGS_TIMEOUT_MS = 2_000L
    }
}

/**
 * Asks to be told about the chosen devices again, after a restart.
 *
 * A companion-device observation is a request this app made, and a request made by a process
 * that no longer exists is not something to assume the system still holds. Re-arming on boot
 * costs a few milliseconds and removes the failure mode where the feature works until the
 * phone is restarted and then silently never works again — which is indistinguishable, from
 * the outside, from it having been broken all along.
 *
 * Also re-armed whenever the app starts and whenever the list changes; this covers the case
 * where neither happens because nobody has opened the app since the restart.
 */
class AutoPlayBootReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pending = goAsync()
        scope.launch {
            try {
                runCatching {
                    val graph = dependencies(context)
                    val settings = withTimeoutOrNull(SETTINGS_TIMEOUT_MS) {
                        graph.playbackPrefs().settings.first()
                    }?.autoPlay ?: return@runCatching
                    if (!settings.armed) return@runCatching
                    graph.companionDevices().observe(settings.devices)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val SETTINGS_TIMEOUT_MS = 5_000L
    }
}
