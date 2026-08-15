package io.github.lightheaded.lugu.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.sync.PlayerSettings
import io.github.lightheaded.lugu.core.sync.TransportButton
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The notification's buttons, which were wrong for two separate reasons at once: they wore
 * previous/next icons while performing a skip, and their order was Media3's rather than the
 * listener's. Neither is something a type checker can object to, which is what this is for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(UnstableApi::class)
class NotificationLayoutTest {

    private fun settings(back: Int, forward: Int, buttons: List<TransportButton> = emptyList()) =
        PlayerSettings(skipBackSec = back, skipForwardSec = forward, notificationButtons = buttons)

    @Test
    fun `a skip icon says the number the button really moves`() {
        listOf(
            5 to CommandButton.ICON_SKIP_BACK_5,
            10 to CommandButton.ICON_SKIP_BACK_10,
            15 to CommandButton.ICON_SKIP_BACK_15,
            30 to CommandButton.ICON_SKIP_BACK_30,
        ).forEach { (seconds, icon) ->
            val chosen = NotificationLayout.iconFor(TransportButton.SKIP_BACK, settings(seconds, 30))
            assertThat(chosen).isEqualTo(icon)
        }

        listOf(
            5 to CommandButton.ICON_SKIP_FORWARD_5,
            10 to CommandButton.ICON_SKIP_FORWARD_10,
            15 to CommandButton.ICON_SKIP_FORWARD_15,
            30 to CommandButton.ICON_SKIP_FORWARD_30,
        ).forEach { (seconds, icon) ->
            val chosen =
                NotificationLayout.iconFor(TransportButton.SKIP_FORWARD, settings(15, seconds))
            assertThat(chosen).isEqualTo(icon)
        }
    }

    /**
     * The rule that matters: Media3 has no icon for 20, 45, 60 or 90 seconds, and a button
     * labelled 30 that moves 45 is worse than one that carries a plain arrow.
     */
    @Test
    fun `a skip with no matching icon gets the plain arrow rather than a wrong number`() {
        listOf(20, 45, 60, 90, 7).forEach { seconds ->
            assertThat(NotificationLayout.iconFor(TransportButton.SKIP_BACK, settings(seconds, 30)))
                .isEqualTo(CommandButton.ICON_SKIP_BACK)
            assertThat(
                NotificationLayout.iconFor(TransportButton.SKIP_FORWARD, settings(15, seconds)),
            ).isEqualTo(CommandButton.ICON_SKIP_FORWARD)
        }
    }

    @Test
    fun `chapter buttons keep the icons a chapter jump deserves`() {
        val any = settings(15, 30)
        assertThat(NotificationLayout.iconFor(TransportButton.PREVIOUS_CHAPTER, any))
            .isEqualTo(CommandButton.ICON_PREVIOUS)
        assertThat(NotificationLayout.iconFor(TransportButton.NEXT_CHAPTER, any))
            .isEqualTo(CommandButton.ICON_NEXT)
    }

    /** Whenever the icon cannot carry the number, the label is the only place it appears. */
    @Test
    fun `a seek label states the size of the jump`() {
        val chosen = settings(back = 45, forward = 90)
        assertThat(NotificationLayout.labelFor(TransportButton.SKIP_BACK, chosen))
            .isEqualTo("Back 45 seconds")
        assertThat(NotificationLayout.labelFor(TransportButton.SKIP_FORWARD, chosen))
            .isEqualTo("Forward 90 seconds")
    }

    @Test
    fun `every button sends a command of its own`() {
        val commands = TransportButton.entries.map { NotificationLayout.commandFor(it) }
        assertThat(commands).containsNoDuplicates()
    }

    @Test
    fun `the first two buttons take the places either side of play-pause`() {
        assertThat(NotificationLayout.slotsFor(0).first()).isEqualTo(CommandButton.SLOT_BACK)
        assertThat(NotificationLayout.slotsFor(1).first()).isEqualTo(CommandButton.SLOT_FORWARD)
        assertThat(NotificationLayout.slotsFor(2).toList()).containsExactly(CommandButton.SLOT_OVERFLOW)
        assertThat(NotificationLayout.slotsFor(5).toList()).containsExactly(CommandButton.SLOT_OVERFLOW)
    }

    /** A button whose place is reserved by the surface must appear somewhere, not vanish. */
    @Test
    fun `a side button falls back to the overflow rather than disappearing`() {
        listOf(0, 1).forEach { position ->
            assertThat(NotificationLayout.slotsFor(position).toList())
                .contains(CommandButton.SLOT_OVERFLOW)
        }
    }

    /** Tap order is display order; that is the whole of the setting. */
    @Test
    fun `the listener's order is the order the buttons come out in`() {
        val chosen = settings(
            back = 15,
            forward = 30,
            buttons = listOf(
                TransportButton.NEXT_CHAPTER,
                TransportButton.SKIP_BACK,
                TransportButton.SKIP_FORWARD,
            ),
        )

        val buttons = NotificationLayout.buttonsFor(chosen)

        assertThat(buttons.map { it.sessionCommand?.customAction }).containsExactly(
            NotificationLayout.COMMAND_CHAPTER_NEXT,
            NotificationLayout.COMMAND_SKIP_BACK,
            NotificationLayout.COMMAND_SKIP_FORWARD,
        ).inOrder()
        assertThat(buttons[0].slots.get(0)).isEqualTo(CommandButton.SLOT_BACK)
        assertThat(buttons[1].slots.get(0)).isEqualTo(CommandButton.SLOT_FORWARD)
        assertThat(buttons[2].slots.get(0)).isEqualTo(CommandButton.SLOT_OVERFLOW)
    }

    /** No buttons is a real answer: the notification keeps play-pause and nothing else. */
    @Test
    fun `an empty setting offers nothing at all`() {
        assertThat(NotificationLayout.buttonsFor(settings(15, 30, emptyList()))).isEmpty()
    }

    /**
     * Commands are granted once, at connection. If the set depended on the settings, a
     * layout pushed after a change would arrive carrying a command the controller had never
     * been given, and the button would render disabled.
     */
    @Test
    fun `every command a button could send is offered up front`() {
        val offered = NotificationLayout.allCommands().map { it.customAction }
        TransportButton.entries.forEach {
            assertThat(offered).contains(NotificationLayout.commandFor(it))
        }
    }
}
