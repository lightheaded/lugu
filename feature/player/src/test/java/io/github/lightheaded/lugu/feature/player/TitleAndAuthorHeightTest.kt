package io.github.lightheaded.lugu.feature.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The line under the title keeps its height, so a start moves nothing.
 *
 * While a new item starts, the credit line shows "Starting…". If the old item had no author,
 * that line used to appear from nothing and push the controls down by one line. The mini
 * player always draws its subtitle line, also when it is empty, so it does not have the
 * fault. Its test stays here to keep it that way.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TitleAndAuthorHeightTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the header is the same height with an author, with no author and while starting`() {
        var author: String? by mutableStateOf("Ada Example")
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp).testTag(HEADER)) {
                    TitleAndAuthor(
                        title = "A book",
                        author = author,
                        enabled = true,
                        onOpen = {},
                        onOpenAuthor = {},
                    )
                }
            }
        }

        val withAuthor = height()
        author = null
        val withoutAuthor = height()
        author = STARTING_LABEL
        val starting = height()

        assertThat(withoutAuthor).isEqualTo(withAuthor)
        assertThat(starting).isEqualTo(withAuthor)
    }

    @Test
    fun `an empty credit line is not read out and takes no tap`() {
        compose.setContent {
            MaterialTheme {
                Column {
                    TitleAndAuthor(title = "A book", author = null, enabled = true, onOpen = {}, onOpenAuthor = {})
                }
            }
        }

        // The title is the only node with text or a click: the reserved line has no semantics.
        compose.onAllNodesWithText("", substring = true).assertCountEquals(1)
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun `the mini player's title stays in place with a subtitle, with none and while starting`() {
        var subtitle by mutableStateOf("Ada Example")
        compose.setContent {
            MaterialTheme {
                MiniPlayerBar(
                    title = "A book",
                    subtitle = subtitle,
                    coverUrl = null,
                    progress = 0f,
                    isPlaying = false,
                    onOpen = {},
                    onPlayPause = {},
                )
            }
        }

        val withSubtitle = titleTop()
        subtitle = ""
        val withoutSubtitle = titleTop()
        subtitle = STARTING_LABEL
        val starting = titleTop()

        assertThat(withoutSubtitle).isEqualTo(withSubtitle)
        assertThat(starting).isEqualTo(withSubtitle)
    }

    private fun titleTop() = compose.onNodeWithText("A book").getUnclippedBoundsInRoot().top.value

    private fun height() = compose.onNodeWithTag(HEADER).getUnclippedBoundsInRoot().height.value

    private companion object {
        const val HEADER = "header"
    }
}
