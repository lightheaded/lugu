package io.github.lightheaded.lugu.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** The kind of thing the audio is going to, as far as the resume settings care. */
enum class AudioRouteClass {
    HEADPHONES,
    CAR,

    /** The phone's own speaker, a television, anything not worth resuming for. */
    OTHER,
}

/**
 * Which class of output a device belongs to.
 *
 * A car and a pair of headphones are the same Bluetooth profile — `TYPE_BLUETOOTH_A2DP`
 * covers both — and the only way to tell them apart from the device itself is to read the
 * remote device's `BluetoothClass`, which needs the `BLUETOOTH_CONNECT` permission on
 * Android 12 and later. Asking for a Bluetooth permission to decide whether to press play
 * is not a trade worth making, so the car is identified by the phone being in car mode
 * instead: Android Auto and every car dock put the phone into `UI_MODE_TYPE_CAR`, and a
 * projected car session is the same signal from the other direction.
 *
 * Wired outputs are never treated as a car. A car with a cable is possible, but the
 * asymmetry in the settings — resume in the car, do not resume for headphones — exists
 * because a car connecting means the engine started, and plugging in a cable does not
 * mean that whatever it is plugged into is a car.
 */
object AudioRoutes {

    fun classify(deviceType: Int, isCarMode: Boolean): AudioRouteClass = when (deviceType) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> AudioRouteClass.HEADPHONES

        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        TYPE_BLE_HEADSET,
        TYPE_BLE_BROADCAST,
        -> if (isCarMode) AudioRouteClass.CAR else AudioRouteClass.HEADPHONES

        else -> AudioRouteClass.OTHER
    }

    /**
     * Bluetooth Low Energy audio arrived in Android 12. The values are compile-time
     * constants that are only ever compared against, so naming them here rather than
     * calling into the newer API keeps this usable at the minimum supported version.
     */
    private const val TYPE_BLE_HEADSET = 26
    private const val TYPE_BLE_BROADCAST = 30
}

/**
 * Watches audio outputs coming and going.
 *
 * `AudioManager.registerAudioDeviceCallback` is used rather than the Bluetooth connection
 * broadcasts because it needs no permission at all, reports wired and Bluetooth devices
 * through one path, and — unlike `ACTION_AUDIO_BECOMING_NOISY` — also fires when a device
 * *arrives*, which is the half of this that the resume settings need.
 *
 * The callback reports every device, including the phone's own speaker and earpiece, so
 * anything that classifies as [AudioRouteClass.OTHER] is dropped: the speaker becoming
 * available is not a reason to start a book playing out loud.
 */
internal class AudioRouteWatcher(
    context: Context,
    private val isCarMode: () -> Boolean,
    private val onLost: (AudioRouteClass) -> Unit,
    private val onGained: (AudioRouteClass) -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Registering delivers the devices that are already connected, which would otherwise
     * read as every output having just arrived — and resume a book the moment the service
     * starts. That first delivery is the inventory, not an event.
     */
    private var seenInitialDevices = false

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            if (!seenInitialDevices) {
                seenInitialDevices = true
                return
            }
            interestingClasses(addedDevices).forEach(onGained)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            interestingClasses(removedDevices).forEach(onLost)
        }
    }

    fun start() {
        seenInitialDevices = false
        audioManager?.registerAudioDeviceCallback(callback, handler)
    }

    fun stop() {
        audioManager?.unregisterAudioDeviceCallback(callback)
    }

    private fun interestingClasses(devices: Array<out AudioDeviceInfo>?): List<AudioRouteClass> {
        val carMode = isCarMode()
        return devices.orEmpty()
            .filter { it.isSink }
            .map { AudioRoutes.classify(it.type, carMode) }
            .filter { it != AudioRouteClass.OTHER }
            .distinct()
    }
}
