package io.github.lightheaded.lugu.harness

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.IBinder
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Takes lugu off the network, and puts the device back as it was found.
 *
 * [OfflineVpnService] holds the reasoning about the route: one package loses every route, on
 * both API levels, by the same command. This object is the part the tests use — consent, the
 * bind, and a restore that runs even after a failure.
 *
 * ### The one shell command, and why it is tried four ways
 *
 * A VPN needs the consent of the user. A test cannot tap a system dialog that appears over
 * the app it is watching, so the shell grants the consent instead: the `ACTIVATE_VPN` app op
 * makes [VpnService.prepare] answer null, which is the platform's own word for "this package
 * is allowed".
 *
 * The name of the command moved over the years. `appops` and `cmd appops` are both real on
 * some levels, and the op is spelled both `ACTIVATE_VPN` and `android:activate_vpn`. Rather
 * than guess from the API level, the helper tries each form and asks the platform after each
 * one whether the consent is now in place. The answer is measured, not assumed, and the same
 * form is used again to put the op back.
 */
internal object LuguOffline {

    private var connection: ServiceConnection? = null
    private var control: OfflineVpnService.Control? = null
    private var granted: ((String, String) -> String)? = null

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Whether lugu is off the network at this moment.
     *
     * Read again at the end of a check, not only at the start. A tunnel can be taken away —
     * by another VPN, or by a person — and a library that renders after that renders with a
     * server in reach, which is the one way this check can pass for the wrong reason.
     */
    val isCut: Boolean get() = control?.isCut == true

    /**
     * Takes lugu off the network.
     *
     * @return null when lugu has no path to any network, or the reason why it still has one.
     *   A reason is a failure of the harness rather than of lugu, so the caller reports it as
     *   one.
     */
    fun cut(): String? {
        consent()?.let { return it }
        val open = control ?: bind() ?: return "the harness could not bind its own $SERVICE"
        if (!open.cut(Lugu.PACKAGE)) {
            return "$SERVICE was bound and the platform still refused a tunnel for lugu"
        }
        Log.i(TAG, "lugu is off the network: every route of ${Lugu.PACKAGE} goes into a tunnel")
        return null
    }

    /**
     * Gives lugu its network back, closes the service and puts the app op back.
     *
     * Best effort in every step, because this runs after a test that may have failed, and a
     * cleanup that threw would replace the real failure with its own. The platform is the
     * backstop: the tunnel belongs to this process, so it closes with the process even if
     * every line here is skipped.
     */
    fun restore() {
        runCatching { control?.restore() }.onFailure { Log.w(TAG, "the tunnel did not close", it) }
        control = null

        connection?.let { open ->
            connection = null
            runCatching { context.unbindService(open) }
                .onFailure { Log.w(TAG, "$SERVICE did not unbind", it) }
        }

        granted?.let { command ->
            granted = null
            runCatching { Shell.run(command(context.packageName, "default")) }
                .onFailure { Log.w(TAG, "the ACTIVATE_VPN app op was not put back", it) }
        }
    }

    /** @return null when this package may open a tunnel, or the reason why it may not. */
    private fun consent(): String? {
        if (VpnService.prepare(context) == null) return null

        for (command in CONSENT_COMMANDS) {
            Shell.run(command(context.packageName, "allow"))
            if (VpnService.prepare(context) == null) {
                granted = command
                return null
            }
        }
        return "no form of the appops command turned ACTIVATE_VPN on for " +
            "${context.packageName}, so the platform will not let the harness open a tunnel"
    }

    private fun bind(): OfflineVpnService.Control? {
        val connected = CountDownLatch(1)
        var bound: OfflineVpnService.Control? = null
        val pending = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                bound = service as? OfflineVpnService.Control
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }

        val asked = context.bindService(
            Intent(context, OfflineVpnService::class.java),
            pending,
            Context.BIND_AUTO_CREATE,
        )
        if (!asked) return null
        // Recorded before the wait, so a bind that answers late is still unbound afterwards.
        connection = pending

        if (!connected.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
        control = bound
        return bound
    }

    private const val TAG = "LuguHarness"
    private const val SERVICE = "OfflineVpnService"
    private const val BIND_TIMEOUT_MS = 10_000L

    /**
     * Every spelling of the one command, newest first. The mode is the last word, so the
     * same entry grants the op with `allow` and puts it back with `default`.
     */
    private val CONSENT_COMMANDS: List<(String, String) -> String> = listOf(
        { pkg, mode -> "cmd appops set $pkg ACTIVATE_VPN $mode" },
        { pkg, mode -> "appops set $pkg ACTIVATE_VPN $mode" },
        { pkg, mode -> "cmd appops set $pkg android:activate_vpn $mode" },
        { pkg, mode -> "appops set $pkg android:activate_vpn $mode" },
    )
}
