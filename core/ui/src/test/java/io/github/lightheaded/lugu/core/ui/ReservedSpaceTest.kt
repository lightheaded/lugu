package io.github.lightheaded.lugu.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Reserved space, and the one thing it promises: the height does not change.
 *
 * This is the half of the "nothing may move" rule that cannot be got by drawing over the
 * content. A message under a password box has to sit there, so what is asserted is that the
 * block it sits in is the same size when there is nothing to say — because the fault being
 * removed is a Sign in button that moves out from under the thumb that just pressed it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReservedSpaceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a message occupies the same space as no message`() {
        var message by mutableStateOf<String?>(null)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.testTag(BLOCK).width(200.dp)) { ReservedMessage(message) }
            }
        }

        val empty = heightOfBlock()
        assertThat(empty).isGreaterThan(0)

        message = "Wrong username or password"
        compose.waitForIdle()
        assertThat(heightOfBlock()).isEqualTo(empty)

        // Two lines are reserved, so a message that needs both must not grow the block
        // either. A longer one is cut rather than allowed to push the button down.
        message = "That address could not be saved, and the reason the server gave was " +
            "that it is already in use by another account on this device"
        compose.waitForIdle()
        assertThat(heightOfBlock()).isEqualTo(empty)

        message = null
        compose.waitForIdle()
        assertThat(heightOfBlock()).isEqualTo(empty)
    }

    /**
     * Standing text the screen supplies itself: the same words every time it is true, so
     * composing it always and hiding it costs nothing to measure.
     */
    @Test
    fun `standing text keeps its space and is not read out while it is hidden`() {
        var visible by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.testTag(BLOCK).width(200.dp)) {
                    Text(WARNING, modifier = Modifier.reservedSpace(visible))
                }
            }
        }

        val hidden = heightOfBlock()
        // Hidden from a screen reader as well as from the eye. Reserved space that is
        // read out is an empty line announced to somebody who cannot see it is not there.
        compose.onNodeWithText(WARNING).assertDoesNotExist()

        visible = true
        compose.waitForIdle()
        assertThat(heightOfBlock()).isEqualTo(hidden)
        compose.onNodeWithText(WARNING).assertIsDisplayed()
    }

    private fun heightOfBlock(): Int =
        compose.onNodeWithTag(BLOCK).fetchSemanticsNode().size.height

    private companion object {
        const val BLOCK = "reserved-block"
        const val WARNING = "This address is plain HTTP. Use https:// if your server offers it."
    }
}
