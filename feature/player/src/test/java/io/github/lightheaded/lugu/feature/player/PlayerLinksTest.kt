package io.github.lightheaded.lugu.feature.player

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.sync.BrowseKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Where each tap on the player leads (issue #2).
 *
 * The destinations are asserted on [titleLink] and [authorLink], and the taps on the real
 * [TitleAndAuthor], because [PlayerScreen] needs a bound media session that a JVM test
 * cannot make. The app module turns each [PlayerLink] into a route; `PlayerLinkRoutesTest`
 * there checks that half.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlayerLinksTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the title of an episode opens the episode, not the show`() {
        assertThat(titleLink("li_show", "ep_7")).isEqualTo(PlayerLink.Episode("li_show", "ep_7"))
    }

    @Test
    fun `the title of a book opens the book`() {
        assertThat(titleLink("li_book", null)).isEqualTo(PlayerLink.Item("li_book"))
    }

    @Test
    fun `the credit of a book opens the author page`() {
        assertThat(authorLink("li_book", null, "Ada Example"))
            .isEqualTo(PlayerLink.Group(BrowseKind.AUTHORS.id, "Ada Example"))
    }

    @Test
    fun `the credit of an episode opens the show`() {
        assertThat(authorLink("li_show", "ep_7", "Harbour Radio")).isEqualTo(PlayerLink.Item("li_show"))
    }

    @Test
    fun `no credit, no link`() {
        assertThat(authorLink("li_book", null, null)).isNull()
        assertThat(authorLink("li_book", null, "  ")).isNull()
    }

    @Test
    fun `a tap on the title and a tap on the credit go to different places`() {
        val opened = mutableListOf<PlayerLink>()
        compose.setContent {
            // In a Column, as on the player: the composable emits two lines and no layout.
            MaterialTheme {
                Column {
                    TitleAndAuthor(
                        title = "Episode seven",
                        author = "Harbour Radio",
                        enabled = true,
                        onOpen = { opened += titleLink("li_show", "ep_7") },
                        onOpenAuthor = { opened += authorLink("li_show", "ep_7", "Harbour Radio")!! },
                    )
                }
            }
        }

        compose.onNodeWithText("Episode seven").performClick()
        compose.onNodeWithText("Harbour Radio").performClick()

        assertThat(opened).containsExactly(
            PlayerLink.Episode("li_show", "ep_7"),
            PlayerLink.Item("li_show"),
        ).inOrder()
    }

    @Test
    fun `a credit with nowhere to go takes no tap`() {
        compose.setContent {
            MaterialTheme {
                Column {
                    TitleAndAuthor(title = "Untitled", author = "Someone", enabled = true, onOpen = {})
                }
            }
        }

        compose.onNodeWithText("Untitled").assertHasClickAction()
        compose.onNodeWithText("Someone").assertHasNoClickAction()
    }
}
