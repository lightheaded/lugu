package io.github.lightheaded.lugu.feature.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The show's name in the episode page's bar is the way to the show.
 *
 * A reader who came from a new-episode notification has only Home under this page, so
 * without the link the show is three taps and a search away.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class EpisodeTopBarTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a tap on the show's name opens that show`() {
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                EpisodeTopBar(
                    showTitle = "Coastal Signal",
                    showItemId = "li_pod",
                    onBack = {},
                    onOpenShow = { opened += it },
                )
            }
        }

        compose.onNodeWithText("Coastal Signal").assertHasClickAction().performClick()

        assertThat(opened).containsExactly("li_pod")
    }

    /** Before the episode loads, and after a feed withdraws it, there is no show to open. */
    @Test
    fun `with no show to open the name is plain text`() {
        compose.setContent {
            MaterialTheme {
                EpisodeTopBar(
                    showTitle = "Coastal Signal",
                    showItemId = null,
                    onBack = {},
                    onOpenShow = { error("Nothing to open") },
                )
            }
        }

        compose.onNodeWithText("Coastal Signal").assertHasNoClickAction()
    }
}
