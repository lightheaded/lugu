package io.github.lightheaded.lugu.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import io.github.lightheaded.lugu.core.model.formatSpeed

/**
 * The car's speed button, which prints the rate in force.
 *
 * The first attempt put the rate in the button's display name, and a driver saw no change.
 * Android Auto's media template draws a custom action as its **icon** alone. The display
 * name reaches the host as a name for a screen reader and for a tooltip. It is never text on
 * the button. An app that shows "1.2x" in a car draws those characters inside the icon.
 *
 * Media3 1.11.0 already ships those icons. `ICON_PLAYBACK_SPEED_0_5` through
 * `ICON_PLAYBACK_SPEED_2_0` are vector drawables whose paths are the digits themselves.
 * `CommandButton.Builder(icon)` turns the constant into a drawable id in this app's own
 * resource table. That id is the one thing a projection host can resolve: a legacy custom
 * action carries an integer resource id and nothing else. A bitmap built at run time
 * therefore reaches no car, whatever `content://` grant it holds. The shipped drawables are
 * not merely the cheapest route. They are the only route that works.
 *
 * The cost is that seven rates have an icon and no other rate does. The presets belong to
 * the listener, and the fine adjustment in the player moves in steps of 0.05. A rate such as
 * 1.35x therefore has no icon of its own. It falls back to the plain speed icon, and the
 * display name still names it. This follows the rule [NotificationLayout.iconFor] already
 * obeys for skip durations: a numbered icon is used only when the number on it is true.
 *
 * Everything here is a pure function of the rate, so the choices can be held to in a test.
 */
@OptIn(UnstableApi::class)
object CarSpeedButton {

    /**
     * The action a press of the speed button sends back.
     *
     * Named here rather than beside the chapter commands, because speed is the car's alone.
     * Named here rather than in the service, because the button is built here.
     */
    const val COMMAND_SPEED_CYCLE = "io.github.lightheaded.lugu.SPEED_CYCLE"

    /**
     * Rates with an icon that prints them, in hundredths.
     *
     * Hundredths rather than a `Float`, because 0.05 has no exact form in binary. Twenty
     * presses of the faster button from 1.0 land on 1.9999990 rather than on 2.0 — see
     * `formatSpeedNumber`. This map and the label round the same way, so the icon and the
     * name never disagree.
     */
    private val ICONS_BY_HUNDREDTHS = mapOf(
        50 to CommandButton.ICON_PLAYBACK_SPEED_0_5,
        80 to CommandButton.ICON_PLAYBACK_SPEED_0_8,
        100 to CommandButton.ICON_PLAYBACK_SPEED_1_0,
        120 to CommandButton.ICON_PLAYBACK_SPEED_1_2,
        150 to CommandButton.ICON_PLAYBACK_SPEED_1_5,
        180 to CommandButton.ICON_PLAYBACK_SPEED_1_8,
        200 to CommandButton.ICON_PLAYBACK_SPEED_2_0,
    )

    /** One button for each rate seen, keyed by the same hundredths. */
    private val buttons = HashMap<Int, CommandButton>()

    /** The rate as the icon and the label see it. */
    private fun hundredthsOf(speed: Float): Int = Math.round(speed * 100f)

    /**
     * The icon that prints this rate, or the plain speed icon when none does.
     *
     * A wrong number is worse than no number. A rate away from the seven drawn ones gets the
     * unnumbered icon rather than the nearest numbered one.
     */
    fun iconFor(speed: Float): Int =
        ICONS_BY_HUNDREDTHS[hundredthsOf(speed)] ?: CommandButton.ICON_PLAYBACK_SPEED

    /**
     * What a screen reader says, and what the expanded notification prints.
     *
     * The word stays in front of the number, because a spoken list has no icon beside the
     * name. "1.2x" alone does not say what is 1.2 times what. The digits come from the
     * project's one speed formatter, so this button, the player's chip and the settings
     * screen cannot drift apart.
     */
    fun labelFor(speed: Float): String = "Speed ${formatSpeed(speed)}"

    /**
     * The button for the rate now in force.
     *
     * Tom asked for the rate in force rather than the rate a press moves to. After a press
     * at 1.0x that lands on 1.2x, the button reads "1.2x". The rate a press moves to reads
     * "1.2x" *before* the press instead. That tells a driver what will happen, and never
     * what did.
     *
     * Cached because the button list is rebuilt on every settings emission, and on every
     * connect of a controller. The set of rates one listener uses is small. Nothing is drawn
     * here, because the icon is a resource that the build already holds. The cache therefore
     * holds buttons rather than images, and no work leaves the thread that calls this.
     */
    fun buttonFor(speed: Float): CommandButton = synchronized(buttons) {
        buttons.getOrPut(hundredthsOf(speed)) {
            CommandButton.Builder(iconFor(speed))
                .setSessionCommand(SessionCommand(COMMAND_SPEED_CYCLE, Bundle.EMPTY))
                .setDisplayName(labelFor(speed))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build()
        }
    }
}
