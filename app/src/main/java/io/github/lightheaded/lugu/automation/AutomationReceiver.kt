package io.github.lightheaded.lugu.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.lightheaded.lugu.BuildConfig
import io.github.lightheaded.lugu.core.sync.PlayerSettings
import io.github.lightheaded.lugu.playback.PlaybackConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives playback from Tasker, Automate, `adb`, or anything else that can send a
 * broadcast.
 *
 * A receiver rather than an activity, because an automation that raises a window to pause
 * a book is worse than no automation at all: it interrupts whatever is on screen, and in a
 * car it takes over the display.
 *
 * The receiver is exported without a permission, and that is a decision rather than an
 * oversight. Everything here is a transport command — the same verbs the notification, a
 * headset button and the car already send — so the worst a hostile caller achieves is a
 * nuisance the listener can see and undo. Against that, the receiver **answers nothing**:
 * it sets no result code and no result data, writes no library content into a log or a
 * toast, and reports no state. There is therefore no path by which a caller learns what is
 * in the library, which server it came from, or anything about the account. A receiver
 * that returns a result string would be a data-exfiltration path; this one only acts.
 *
 * A permission was considered and rejected. A signature-level one no automation app could
 * hold, so the feature would not exist; a normal-level one any caller can declare for
 * itself, so it protects nothing and only adds a line to an install screen; a dangerous
 * one has no way of being prompted for from a broadcast, so it would leave routines
 * failing silently. None of them buys what the silence above already gives.
 *
 * The connection is taken from the singleton graph rather than through a view model, in
 * the same way `MainActivity` does it for a spoken request: an automation has to be
 * honoured whether or not any screen of the app has ever been composed. It is read through
 * an entry point rather than with `@AndroidEntryPoint`, because Hilt's receiver support
 * requires calling `super.onReceive`, and that member is abstract on `BroadcastReceiver` —
 * which Kotlin, unlike Java, refuses to compile.
 */
class AutomationReceiver : BroadcastReceiver() {

    /** The one dependency this receiver has: everything it can do is a transport command. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Dependencies {
        fun playbackConnection(): PlaybackConnection
    }

    /**
     * Outlives the receiver instance on purpose: a receiver is dead the moment [onReceive]
     * returns, and the one command that needs to read settings first has to survive that.
     * The broadcast is held open for exactly as long as that takes.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onReceive(context: Context, intent: Intent) {
        val command = AutomationActions.parse(
            action = intent.action,
            applicationId = BuildConfig.APPLICATION_ID,
            extras = IntentExtras(intent.extras),
        ) ?: return

        // An exception thrown out of a receiver takes the process with it, and this
        // receiver runs on somebody else's schedule rather than the listener's. A
        // malformed broadcast must not be able to end a book that is playing.
        runCatching {
            val playback = EntryPointAccessors
                .fromApplication(context.applicationContext, Dependencies::class.java)
                .playbackConnection()
            dispatch(playback, command)
        }
    }

    private fun dispatch(playback: PlaybackConnection, command: AutomationCommand) {
        when (command) {
            // The connection has a toggle and a pause but no bare resume, and the toggle
            // is the wrong verb here: a routine that fires twice must not pause what it
            // started.
            AutomationCommand.Play ->
                if (!playback.state.value.isPlaying) playback.togglePlayPause()
            AutomationCommand.Pause -> playback.pause()
            AutomationCommand.TogglePlayPause -> playback.togglePlayPause()
            AutomationCommand.NextChapter -> playback.nextChapter()
            AutomationCommand.PreviousChapter -> playback.previousChapter()
            is AutomationCommand.SetSpeed -> playback.setSpeed(command.speed)
            is AutomationCommand.SetSleepTimer -> playback.setSleepTimer(command.mode)
            is AutomationCommand.PlayFromSearch -> playback.playFromSearch(command.query)
            is AutomationCommand.Skip -> skip(playback, command.forward, command.seconds)
        }
    }

    private fun skip(playback: PlaybackConnection, forward: Boolean, seconds: Double?) {
        if (seconds != null) {
            playback.seekBy(if (forward) seconds else -seconds)
            return
        }
        /*
         * No distance was given, so the automation moves as far as the button in the app
         * does — someone who has set their skip to twenty seconds means twenty here too.
         * Reading that suspends, hence the broadcast is held open; the timeout is there
         * because a settings store that never answers must not hold it open for the ten
         * seconds Android allows before it kills the process.
         */
        val pending = goAsync()
        scope.launch {
            try {
                val settings = withTimeoutOrNull(SETTINGS_TIMEOUT_MS) { playback.settings.first() }
                    ?: PlayerSettings()
                val amount = if (forward) settings.skipForwardSec else -settings.skipBackSec
                playback.seekBy(amount.toDouble())
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val SETTINGS_TIMEOUT_MS = 2_000L
    }
}

/**
 * A received bundle, read through [AutomationExtras].
 *
 * Values are read for what they mean rather than for the type they arrived as, because the
 * sender chooses that type and most automation apps send text. The deprecated untyped
 * getter is the only way to do that: the typed ones require knowing in advance whether the
 * caller sent an int, a long, a float or a string, and getting it wrong reads as absent.
 */
private class IntentExtras(private val bundle: Bundle?) : AutomationExtras {

    @Suppress("DEPRECATION")
    private fun raw(key: String): Any? = bundle?.get(key)

    override fun has(key: String): Boolean = bundle?.containsKey(key) == true

    override fun number(key: String): Double? = when (val value = raw(key)) {
        is Number -> value.toDouble()
        is CharSequence -> value.toString().trim().toDoubleOrNull()
        else -> null
    }

    override fun text(key: String): String? = when (val value = raw(key)) {
        is CharSequence -> value.toString()
        else -> null
    }

    override fun flag(key: String): Boolean? = when (val value = raw(key)) {
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        // An automation app with no boolean type sends whichever of these its author
        // reached for first, and all of them plainly mean the same thing.
        is CharSequence -> when (value.toString().trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
        else -> null
    }
}
