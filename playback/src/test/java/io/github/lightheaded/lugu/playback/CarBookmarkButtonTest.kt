package io.github.lightheaded.lugu.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The car's bookmark button.
 *
 * No test can look at a car, so this holds to the four choices that decide what a car
 * receives: the icon for a state, the words of the label, one button for one state, and the
 * command a press sends back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(UnstableApi::class)
class CarBookmarkButtonTest {

    @Test
    fun `the icon fills only when the book already has a bookmark`() {
        assertThat(CarBookmarkButton.iconFor(hasBookmarks = false))
            .isEqualTo(CommandButton.ICON_BOOKMARK_UNFILLED)
        assertThat(CarBookmarkButton.iconFor(hasBookmarks = true))
            .isEqualTo(CommandButton.ICON_BOOKMARK_FILLED)
    }

    @Test
    fun `the first bookmark of a book changes the icon, which is the only feedback a car gets`() {
        assertThat(CarBookmarkButton.iconFor(hasBookmarks = false))
            .isNotEqualTo(CarBookmarkButton.iconFor(hasBookmarks = true))
    }

    @Test
    fun `the label names the action first and the state after it`() {
        assertThat(CarBookmarkButton.labelFor(hasBookmarks = false))
            .isEqualTo("Bookmark this place")
        assertThat(CarBookmarkButton.labelFor(hasBookmarks = true))
            .startsWith("Bookmark this place")
        assertThat(CarBookmarkButton.labelFor(hasBookmarks = true))
            .contains("already has bookmarks")
    }

    @Test
    fun `a button carries the icon and the label of its own state`() {
        listOf(false, true).forEach { hasBookmarks ->
            val button = CarBookmarkButton.buttonFor(hasBookmarks)
            assertThat(button.icon).isEqualTo(CarBookmarkButton.iconFor(hasBookmarks))
            assertThat(button.displayName.toString())
                .isEqualTo(CarBookmarkButton.labelFor(hasBookmarks))
        }
    }

    @Test
    fun `each state resolves to a drawable of its own in this app's resources`() {
        // The one thing a projection host can read is a resource id, so an icon constant
        // that resolved to nothing would reach a car as a blank button. `iconResId` is what
        // Media3 mapped the constant onto, and the two states must not land on one drawable.
        val unfilled = CarBookmarkButton.buttonFor(hasBookmarks = false).iconResId
        val filled = CarBookmarkButton.buttonFor(hasBookmarks = true).iconResId
        assertThat(unfilled).isNotEqualTo(0)
        assertThat(filled).isNotEqualTo(0)
        assertThat(unfilled).isNotEqualTo(filled)
    }

    @Test
    fun `both states send the same command back`() {
        listOf(false, true).forEach { hasBookmarks ->
            assertThat(CarBookmarkButton.buttonFor(hasBookmarks).sessionCommand?.customAction)
                .isEqualTo(CarBookmarkButton.COMMAND_BOOKMARK_ADD)
        }
    }

    @Test
    fun `the command is not one of the notification's`() {
        // A press in a car must not be dispatched as a skip or a chapter move, so the
        // action string has to be its own. `when` in `onCustomCommand` matches on it alone.
        assertThat(NotificationLayout.allCommands().map { it.customAction })
            .doesNotContain(CarBookmarkButton.COMMAND_BOOKMARK_ADD)
        assertThat(CarBookmarkButton.COMMAND_BOOKMARK_ADD)
            .isNotEqualTo(CarSpeedButton.COMMAND_SPEED_CYCLE)
    }

    @Test
    fun `one state, one button`() {
        assertThat(CarBookmarkButton.buttonFor(true))
            .isSameInstanceAs(CarBookmarkButton.buttonFor(true))
        assertThat(CarBookmarkButton.buttonFor(false))
            .isSameInstanceAs(CarBookmarkButton.buttonFor(false))
    }
}
