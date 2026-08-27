package io.github.lightheaded.lugu.harness

import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * The network cut that the offline check needs, made for one package and for nothing else.
 *
 * A VPN owns every route of the apps that it names. This one names lugu, and it never reads
 * the tunnel. While the tunnel is open, no packet from lugu reaches any network.
 *
 * ### Why not airplane mode
 *
 * The manual line says "airplane mode". No shell command turns airplane mode on for both of
 * the API levels that CI runs, and the two legs must make the same claim:
 *
 *  - `cmd connectivity airplane-mode enable` starts at API 30. The API 26 leg does not have
 *    it.
 *  - `settings put global airplane_mode_on 1` moves a flag. The radios follow the
 *    `android.intent.action.AIRPLANE_MODE` broadcast, which is protected: the platform takes
 *    it from the system and from root, and the shell is neither. So the write alone is a
 *    different state from airplane mode.
 *  - `svc wifi disable` leaves mobile data up, and an emulator has mobile data. `svc data`
 *    needs `MODIFY_PHONE_STATE`, which is not dependable across devices and versions.
 *
 * A check that runs on one leg and skips on the other is worse than the manual line, because
 * CI fails every skip. So the harness cuts the network itself, with two things that are as
 * old as this module: [VpnService] and one app op.
 *
 * ### What it claims, and what it does not
 *
 * The claim is exact and it is the same on both legs: lugu has no path to any network. It is
 * narrower than airplane mode in two ways, and both are deliberate.
 *
 *  - The radios stay on and `Settings.Global.AIRPLANE_MODE_ON` stays at 0. Code that reads
 *    that flag is not exercised. lugu reads no such flag.
 *  - lugu still sees a default network, and every request on it fails. Airplane mode instead
 *    takes the network away. For a screen that must come out of the database, the failed
 *    request is the harder of the two: an app that shows a spinner until a request answers
 *    passes the airplane case and fails this one.
 *
 * ### Why this is also the safer route
 *
 * The tunnel belongs to this process, and only lugu goes into it. Nothing else on the device
 * changes, so adb, the emulator and every other test keep their network. If this process
 * dies, the platform closes the tunnel with it. A test that turns a radio off can instead
 * leave the whole device offline for the next job.
 *
 * ### It must stay in the manifest
 *
 * The platform resolves this class by name before it opens a tunnel, and it refuses a class
 * that does not ask for `android.permission.BIND_VPN_SERVICE`. So the declaration in
 * `harness/src/main/AndroidManifest.xml` is part of the mechanism, not decoration. The
 * service does not need to run: the test binds it, and the tunnel lives as long as the
 * descriptor stays open.
 */
class OfflineVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null

    /**
     * What the test holds while lugu is offline.
     *
     * The system binds this service with [VpnService.SERVICE_INTERFACE] to learn about a
     * revoke. The test binds it by class. The two binds must not get the same object, which
     * is why [onBind] hands the system call back to the superclass.
     */
    inner class Control : Binder() {

        /** @return true when lugu has no path to any network. */
        fun cut(packageName: String): Boolean = openTunnel(packageName)

        /** Gives lugu its routes back. Safe to call when the tunnel is already closed. */
        fun restore() = closeTunnel()

        val isCut: Boolean get() = tunnel != null
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == VpnService.SERVICE_INTERFACE) super.onBind(intent) else Control()

    /** The user, or another VPN, took the tunnel away. Nothing here needs to fight it. */
    override fun onRevoke() {
        closeTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTunnel()
        super.onDestroy()
    }

    /**
     * Opens the tunnel for one package.
     *
     * Both address families get an address and a default route. Only one of them is enough
     * for an emulator today, and a device that also has IPv6 would carry the requests that
     * the check says are impossible.
     *
     * The DNS server is an address inside the tunnel, so a name lookup dies with the rest of
     * the traffic. Without it the platform can hand lugu the DNS servers of the network
     * underneath, and a lookup that answers is a fact the app did not have to be offline for.
     */
    private fun openTunnel(packageName: String): Boolean {
        if (tunnel != null) return true
        tunnel = Builder()
            .setSession(SESSION)
            .setMtu(MTU)
            .addAddress(TUNNEL_IPV4, IPV4_PREFIX)
            .addAddress(TUNNEL_IPV6, IPV6_PREFIX)
            .addRoute(ALL_IPV4, 0)
            .addRoute(ALL_IPV6, 0)
            .addDnsServer(TUNNEL_DNS)
            .addAllowedApplication(packageName)
            .establish()
        return tunnel != null
    }

    private fun closeTunnel() {
        val open = tunnel ?: return
        tunnel = null
        runCatching { open.close() }.onFailure { Log.w(TAG, "the tunnel did not close", it) }
    }

    private companion object {
        const val TAG = "LuguHarness"

        /** What the platform shows for the VPN while a check runs. */
        const val SESSION = "lugu offline check"

        /**
         * Private addresses only, and away from the 10.0.2.0/24 that an emulator uses for
         * itself. Nothing answers on either of them, which is the point.
         */
        const val TUNNEL_IPV4 = "172.16.0.2"
        const val TUNNEL_DNS = "172.16.0.3"
        const val TUNNEL_IPV6 = "fd00::2"
        const val IPV4_PREFIX = 32
        const val IPV6_PREFIX = 128

        const val ALL_IPV4 = "0.0.0.0"
        const val ALL_IPV6 = "::"

        /** The smallest that IPv6 allows, so the value is legal for both families. */
        const val MTU = 1280
    }
}
