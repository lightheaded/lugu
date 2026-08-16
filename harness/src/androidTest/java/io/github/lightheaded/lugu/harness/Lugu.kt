package io.github.lightheaded.lugu.harness

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry

/**
 * lugu, as another app on the device sees it.
 *
 * Nothing here imports anything of lugu's. The package name, the activity and the
 * broadcast actions are written out because that is what a headset, a car, `adb` and a
 * Tasker routine have: names in a manifest and no compiler to check them against. If one
 * of them is renamed, the tests here fail — which is the point, because a rename that
 * nobody notices breaks every automation anyone has written.
 */
internal object Lugu {

    /**
     * The debug build, which is the one instrumented tests are run against.
     *
     * The suffix is `applicationIdSuffix = ".debug"` in app/build.gradle.kts, and the class
     * names below carry no suffix because the namespace does not move with the id.
     */
    const val PACKAGE = "io.github.lightheaded.lugu.debug"

    private const val ACTIVITY = "io.github.lightheaded.lugu.MainActivity"
    private const val RECEIVER = "io.github.lightheaded.lugu.automation.AutomationReceiver"

    private const val TAG = "LuguHarness"

    /** KEYCODE_MEDIA_PLAY: the key a headset sends, and what `input keyevent 126` sends. */
    private const val KEYCODE_MEDIA_PLAY = 126

    val isInstalled: Boolean
        get() = Shell.run("pm list packages $PACKAGE").lineSequence()
            .any { it.trim() == "package:$PACKAGE" }

    /** The process id lugu is running as, or null when it is not running. */
    fun pid(): Int? {
        val fromPidof = Shell.run("pidof $PACKAGE").trim().split(Regex("\\s+"))
            .firstNotNullOfOrNull { it.toIntOrNull() }
        if (fromPidof != null) return fromPidof
        // `pidof` is a toybox applet and has not always been on every image. `ps` has.
        return Shell.run("ps -A -o PID,NAME").lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size >= 2 && it[1] == PACKAGE }
            ?.get(0)?.toIntOrNull()
    }

    /**
     * What the platform currently holds for lugu, or null when it holds nothing.
     *
     * The clock is read here, next to the command, because the position in the dump is a
     * stamp with a time on it — see [MediaSessionDump.PlaybackSnapshot.positionMs].
     */
    fun session(): MediaSessionDump.PlaybackSnapshot? =
        MediaSessionDump.parse(Shell.run(MediaSessionDump.COMMAND), PACKAGE, SystemClock.elapsedRealtime())

    /**
     * Opens the app, the way a person does.
     *
     * Done before anything is asked of the automation receiver, and not only for the sign-in
     * screen: an app in the background may not start a playback service, so a `PLAY_SEARCH`
     * sent to a lugu that has been closed for hours is refused by the system. That refusal
     * is documented in docs/automation.md and is not a bug in lugu — but a test that hit it
     * would look like one.
     */
    fun launch() {
        Shell.run("am start -W -n $PACKAGE/$ACTIVITY")
    }

    fun forceStop() {
        Shell.run("am force-stop $PACKAGE")
    }

    /**
     * Ends lugu's process the way running out of memory ends it.
     *
     * Deliberately *not* `am force-stop`. Force-stopping additionally puts the package into
     * the stopped state, which the platform holds until a person launches the app again —
     * on Android 15 and later it also cancels every pending intent the app owns, including
     * the one the media session handed the system to receive media buttons. That is a
     * correct and documented refusal to wake an app the user stopped, and it is not what
     * happens when Android reclaims memory from a book that is playing. Killing the process
     * leaves the package's state alone, which is the case the resumption path exists for.
     *
     * `run-as` is what makes this possible without root: the debug build is debuggable, so
     * the shell may become it, and a process may always signal itself. `am crash` is the
     * fallback for an image where `run-as` is unavailable — it is a real process death too,
     * with a dialog that has to be suppressed.
     *
     * @return the process id that was killed.
     */
    fun killProcess(): Int {
        val pid = requireNotNull(pid()) { "lugu was not running, so there was nothing to kill" }

        Shell.run("run-as $PACKAGE /system/bin/kill -9 $pid")
        if (Await.until(KILL_TIMEOUT_MS) { pid() == null }) return pid

        Log.w(TAG, "run-as did not end pid $pid; falling back to am crash")
        Shell.run("settings put global hide_error_dialogs 1")
        try {
            Shell.run("am crash $PACKAGE")
            check(Await.until(KILL_TIMEOUT_MS) { pid() == null }) {
                "lugu's process $pid survived both run-as kill -9 and am crash"
            }
        } finally {
            Shell.run("settings delete global hide_error_dialogs")
        }
        return pid
    }

    /** The headset's play button, sent the way the manual recipe sends it. */
    fun pressPlay() {
        Shell.run("input keyevent $KEYCODE_MEDIA_PLAY")
    }

    /**
     * One of the documented automation broadcasts.
     *
     * Addressed at the component rather than the package: since Android 8 a manifest
     * receiver gets no implicit broadcast, and an action nobody can route is the quietest
     * failure there is.
     */
    fun broadcast(action: String, extras: Intent.() -> Unit = {}) {
        val intent = Intent("$PACKAGE.action.$action")
            .setComponent(ComponentName(PACKAGE, RECEIVER))
            .apply(extras)
        InstrumentationRegistry.getInstrumentation().targetContext.sendBroadcast(intent)
    }

    private const val KILL_TIMEOUT_MS = 10_000L
}
