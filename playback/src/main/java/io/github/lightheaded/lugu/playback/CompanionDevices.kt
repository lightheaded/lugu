package io.github.lightheaded.lugu.playback

import android.annotation.SuppressLint
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lightheaded.lugu.core.model.AutoPlay
import io.github.lightheaded.lugu.core.model.AutoPlayDevice
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Choosing a device to start playing for, and being told when it turns up.
 *
 * ## Why the companion-device API rather than the Bluetooth one
 *
 * The obvious implementation is a receiver for the Bluetooth connection broadcast and a
 * check on the device's address. It cannot work on Android 12 and later, for two separate
 * reasons, and this API answers both of them.
 *
 * The first is permission. Reading which device connected — its address or its name — needs
 * `BLUETOOTH_CONNECT`, a runtime permission whose prompt says the app wants to *find, connect
 * to and determine the relative position of nearby devices*. That is an alarming thing to ask
 * in order to press play, and [AudioRouteWatcher] already documents the decision not to ask
 * it. The companion association asks nothing: the system shows its own picker, the listener
 * chooses their headphones in it, and lugu is told about that one device and no others.
 *
 * The second is that an app in the background may not start a foreground service, and
 * playing a book is a foreground service. A Bluetooth broadcast is not one of the documented
 * exemptions, so a receiver that tried would be delivered the broadcast and then refused the
 * service — the feature would appear to work and never play anything. Associating grants
 * `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`, which is on the exemption
 * list. The association is not a nicety here; it is the only supported way to do this.
 *
 * Before Android 12 there is no association to make and no restriction to be exempted from,
 * so [BluetoothConnectionReceiver] does the same job the old way.
 *
 * ## What the listener has to do
 *
 * The device has to be switched on and in range when it is chosen, because the system's
 * picker offers what it can see rather than what has been paired. That is worth saying on
 * the screen, since "my headphones are not in the list" otherwise looks like a bug.
 */
@Singleton
class CompanionDevices @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val manager: CompanionDeviceManager? by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            null
        } else {
            runCatching {
                context.getSystemService(CompanionDeviceManager::class.java)
            }.getOrNull()
        }
    }

    /**
     * Whether this device can do it at all.
     *
     * The system feature is checked rather than assumed: companion device setup is not
     * present on every build of Android, and a picker that never appears is worse than a
     * screen that says the feature is unavailable.
     */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            manager != null &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    /**
     * Asks the system for its device picker.
     *
     * The picker is shown by the system, not by lugu, and what comes back here is only the
     * means of launching it — [onPicker] hands an `IntentSender` to whatever can start one.
     */
    fun requestAssociation(onPicker: (IntentSender) -> Unit, onFailure: (String) -> Unit) {
        val manager = manager ?: return onFailure("This phone has no companion device support")
        val request = AssociationRequest.Builder()
            // No criteria: everything the system can see is offered, and the listener says
            // which of it is theirs. A name filter would need the name in advance, which is
            // the thing being asked for.
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .setSingleDevice(false)
            .build()

        val callback = object : CompanionDeviceManager.Callback() {
            // Called `onAssociationPending` since Android 13, whose default implementation
            // forwards to this one. Overriding the older name covers both without a branch.
            @Deprecated("Superseded by onAssociationPending, which forwards to it")
            override fun onDeviceFound(intentSender: IntentSender) = onPicker(intentSender)

            override fun onFailure(error: CharSequence?) {
                onFailure(error?.toString() ?: "No device was chosen")
            }
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.associate(request, Executor(Runnable::run), callback)
            } else {
                @Suppress("DEPRECATION")
                manager.associate(request, callback, null)
            }
        }.onFailure { onFailure(it.message ?: "The device picker could not be opened") }
    }

    /**
     * The association the listener has just made, or null if there is not a new one.
     *
     * Read back from the system rather than out of the picker's result, because the result
     * carries a `BluetoothDevice` whose name cannot be read without the permission this whole
     * approach exists to avoid. The association carries a display name the system has already
     * resolved, and asking for our own associations needs nothing.
     */
    fun newlyAssociated(known: Collection<AutoPlayDevice>): AutoPlayDevice? {
        val knownKeys = known.map { it.key }.toSet()
        return associated().firstOrNull { it.key !in knownKeys }
    }

    /** Every device the system has associated with lugu. */
    fun associated(): List<AutoPlayDevice> {
        val manager = manager ?: return emptyList()
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.myAssociations.map { association ->
                    AutoPlayDevice(
                        key = AutoPlay.deviceKey(association.deviceMacAddress?.toString().orEmpty()),
                        name = association.displayName?.toString()?.ifBlank { null } ?: AutoPlay.UNNAMED,
                    )
                }
            } else {
                // Android 12 has no `AssociationInfo` and so no display name at all. The
                // address is all there is, and it is not something to put on screen.
                @Suppress("DEPRECATION")
                manager.associations.map { AutoPlayDevice(AutoPlay.deviceKey(it), AutoPlay.UNNAMED) }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Asks to be told when these devices turn up.
     *
     * Safe to call as often as it is convenient to: observing an already-observed device does
     * nothing, and observing one whose association has been removed behind lugu's back throws
     * rather than corrupting anything, which is why each is attempted separately. Called on
     * every app start and after every change to the list, because an observation that quietly
     * stopped at some point — a reboot, a system update — is a feature that has stopped
     * working with nothing to show for it.
     */
    @SuppressLint("MissingPermission")
    fun observe(devices: Collection<AutoPlayDevice>) {
        val manager = manager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        devices.forEach { device ->
            val address = AutoPlay.addressOf(device.key) ?: return@forEach
            runCatching { startObserving(manager, address) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startObserving(manager: CompanionDeviceManager, address: String) {
        if (Build.VERSION.SDK_INT >= BAKLAVA) {
            val id = associationIdFor(manager, address) ?: return
            manager.startObservingDevicePresence(observingRequest(id))
        } else {
            @Suppress("DEPRECATION")
            manager.startObservingDevicePresence(address)
        }
    }

    /**
     * Stops watching for a device and gives up the association with it.
     *
     * Both halves matter. Dropping the association is what takes back the permissions it
     * granted, so a device removed from the list leaves lugu with no standing claim on
     * anything — removing the row without disassociating would leave the app quietly holding
     * a right to start itself for hardware the listener has said they are done with.
     */
    @SuppressLint("MissingPermission")
    fun forget(device: AutoPlayDevice) {
        val manager = manager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val address = AutoPlay.addressOf(device.key) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= BAKLAVA) {
                associationIdFor(manager, address)?.let {
                    manager.stopObservingDevicePresence(observingRequest(it))
                }
            } else {
                @Suppress("DEPRECATION")
                manager.stopObservingDevicePresence(address)
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                associationIdFor(manager, address)?.let { manager.disassociate(it) }
            } else {
                @Suppress("DEPRECATION")
                manager.disassociate(address)
            }
        }
    }

    /**
     * Android 16 replaced the address-shaped presence calls with request objects, and takes
     * the same one to start observing and to stop.
     */
    @RequiresApi(BAKLAVA)
    private fun observingRequest(associationId: Int): ObservingDevicePresenceRequest =
        ObservingDevicePresenceRequest.Builder().setAssociationId(associationId).build()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun associationIdFor(manager: CompanionDeviceManager, address: String): Int? =
        manager.myAssociations.firstOrNull {
            it.deviceMacAddress?.toString().equals(address, ignoreCase = true)
        }?.id

    private companion object {
        /**
         * Android 16, where the address-shaped calls above were replaced by association-id
         * ones. Named as a constant because `Build.VERSION_CODES.BAKLAVA` is not present in
         * every build of the SDK this compiles against, and a comparison against a number is
         * exactly as correct.
         */
        const val BAKLAVA = 36
    }
}
