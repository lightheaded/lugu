package io.github.lightheaded.lugu.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import io.github.lightheaded.lugu.core.sync.PlayerSettings
import io.github.lightheaded.lugu.core.sync.TransportButton

/**
 * Which buttons the notification and lock screen carry, and what they look like.
 *
 * Media3's default notification provider builds previous / play-pause / next and offers no
 * seek button to choose instead. That is why the side buttons ended up performing the
 * configured skip while still wearing previous/next icons: an arrow that says "next track"
 * and moves fifteen seconds is something the listener has to learn rather than read. The
 * only way out is to stop letting the provider choose, which means custom session commands
 * with icons and an order of our own — this object decides both.
 *
 * Everything here is a pure function of the settings so the choices can be held to in a
 * test. The icon rule in particular is worth a test rather than an eye: a button labelled
 * 30 that moves 15 seconds is worse than an unlabelled one, so a numbered icon is used only
 * when the number on it is the number the button actually moves.
 *
 * The chapter commands are shared with the buttons offered to a car, which needs the same
 * two actions under different circumstances; they are named once, here.
 */
@OptIn(UnstableApi::class)
object NotificationLayout {

    const val COMMAND_SKIP_BACK = "io.github.lightheaded.lugu.SKIP_BACK"
    const val COMMAND_SKIP_FORWARD = "io.github.lightheaded.lugu.SKIP_FORWARD"
    const val COMMAND_CHAPTER_PREVIOUS = "io.github.lightheaded.lugu.CHAPTER_PREVIOUS"
    const val COMMAND_CHAPTER_NEXT = "io.github.lightheaded.lugu.CHAPTER_NEXT"

    /** The action a button sends back through `onCustomCommand`. */
    fun commandFor(button: TransportButton): String = when (button) {
        TransportButton.SKIP_BACK -> COMMAND_SKIP_BACK
        TransportButton.SKIP_FORWARD -> COMMAND_SKIP_FORWARD
        TransportButton.PREVIOUS_CHAPTER -> COMMAND_CHAPTER_PREVIOUS
        TransportButton.NEXT_CHAPTER -> COMMAND_CHAPTER_NEXT
    }

    /**
     * The icon whose number matches the seek, or the plain arrow when none does.
     *
     * Media3 ships numbered skip icons for 5, 10, 15 and 30 seconds only. The settings
     * screen offers 20, 45, 60 and 90 as well, and any value in range can be typed, so
     * most of the time the honest answer is the unnumbered icon.
     */
    fun iconFor(button: TransportButton, settings: PlayerSettings): Int = when (button) {
        TransportButton.SKIP_BACK -> when (settings.skipBackSec) {
            5 -> CommandButton.ICON_SKIP_BACK_5
            10 -> CommandButton.ICON_SKIP_BACK_10
            15 -> CommandButton.ICON_SKIP_BACK_15
            30 -> CommandButton.ICON_SKIP_BACK_30
            else -> CommandButton.ICON_SKIP_BACK
        }

        TransportButton.SKIP_FORWARD -> when (settings.skipForwardSec) {
            5 -> CommandButton.ICON_SKIP_FORWARD_5
            10 -> CommandButton.ICON_SKIP_FORWARD_10
            15 -> CommandButton.ICON_SKIP_FORWARD_15
            30 -> CommandButton.ICON_SKIP_FORWARD_30
            else -> CommandButton.ICON_SKIP_FORWARD
        }

        TransportButton.PREVIOUS_CHAPTER -> CommandButton.ICON_PREVIOUS
        TransportButton.NEXT_CHAPTER -> CommandButton.ICON_NEXT
    }

    /**
     * What a screen reader says, and what the expanded notification prints beside the icon.
     *
     * The seek labels carry the number because the icon often cannot: whenever the plain
     * arrow is used, this is the only place the size of the jump is stated.
     */
    fun labelFor(button: TransportButton, settings: PlayerSettings): String = when (button) {
        TransportButton.SKIP_BACK -> "Back ${settings.skipBackSec} seconds"
        TransportButton.SKIP_FORWARD -> "Forward ${settings.skipForwardSec} seconds"
        TransportButton.PREVIOUS_CHAPTER -> "Previous chapter"
        TransportButton.NEXT_CHAPTER -> "Next chapter"
    }

    /**
     * Where a button sits, from its place in the listener's own ordering.
     *
     * The notification has exactly two places either side of play-pause, so the first two
     * buttons take them and the rest fall into the overflow that only the expanded
     * notification shows. Each slot preference ends in the overflow so a button whose place
     * is already taken — by a surface that reserves it, as a car does — appears somewhere
     * rather than disappearing.
     */
    fun slotsFor(position: Int): IntArray = when (position) {
        0 -> intArrayOf(CommandButton.SLOT_BACK, CommandButton.SLOT_OVERFLOW)
        1 -> intArrayOf(CommandButton.SLOT_FORWARD, CommandButton.SLOT_OVERFLOW)
        else -> intArrayOf(CommandButton.SLOT_OVERFLOW)
    }

    /**
     * The buttons for a given set of settings, in the order the listener put them in.
     *
     * An empty list is a real answer and not an oversight: it leaves the notification with
     * play-pause alone, which is what the settings screen promises.
     */
    fun buttonsFor(settings: PlayerSettings): List<CommandButton> =
        settings.notificationButtons.mapIndexed { position, button ->
            CommandButton.Builder(iconFor(button, settings))
                .setSessionCommand(SessionCommand(commandFor(button), Bundle.EMPTY))
                .setDisplayName(labelFor(button, settings))
                .setSlots(*slotsFor(position))
                .build()
        }

    /**
     * Every command a notification button might ever send.
     *
     * Granted to controllers once, at connection, whatever the settings currently say.
     * Available commands are read when a controller connects and a later change to them is
     * easy to miss; keeping the command set fixed and moving only the *layout* means a
     * setting can change at any moment without anything having to be re-negotiated.
     */
    fun allCommands(): List<SessionCommand> = TransportButton.entries
        .map { SessionCommand(commandFor(it), Bundle.EMPTY) }
}
