package io.github.lightheaded.lugu.playback

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lightheaded.lugu.core.model.AutoPlay
import io.github.lightheaded.lugu.core.model.AutoPlayDevice
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The paired devices, on the versions of Android where asking is free.
 *
 * Android 11 and earlier only, and the counterpart to [CompanionDevices]: there is no
 * association to make on those versions, so the list has to come from the Bluetooth stack
 * directly. That is fine there and only there — `BLUETOOTH` is a normal permission granted
 * at install, so this asks the listener for nothing. From Android 12 the same call needs a
 * runtime permission whose prompt talks about determining the relative position of nearby
 * devices, and the system's own picker replaces it.
 *
 * Paired rather than nearby, deliberately. A device that has been paired is one the listener
 * has already chosen once, which is a better list to pick headphones out of than everything
 * currently in range — and it works with the headphones sitting switched off in a bag.
 */
@Singleton
class PairedDevices @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /** Whether this list is the one to offer, rather than the system's picker. */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.R &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)

    /**
     * Everything paired, by name.
     *
     * Empty on any failure rather than throwing. A phone with Bluetooth switched off, or one
     * whose adapter is unavailable for any of the several reasons it can be, should show an
     * empty list and a line explaining it — not lose the settings screen.
     */
    @SuppressLint("MissingPermission")
    fun paired(): List<AutoPlayDevice> {
        if (!isSupported) return emptyList()
        return runCatching {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            adapter?.bondedDevices.orEmpty()
                .map { device ->
                    AutoPlayDevice(
                        key = AutoPlay.deviceKey(device.address),
                        name = device.name?.takeIf { it.isNotBlank() } ?: AutoPlay.UNNAMED,
                    )
                }
                .sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }
}
