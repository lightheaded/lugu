package io.github.lightheaded.lugu.feature.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.playback.NowPlaying
import io.github.lightheaded.lugu.playback.StartingItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Which item the player and the mini player show while a play request is on its way (the
 * maintainer's bug "play from Home opens the old item on a bad network").
 *
 * The choice is asserted on [playerHeader], and the mini player's slots on the real
 * [MiniPlayerBar], because [PlayerScreen] and [MiniPlayer] need a bound media session that a
 * JVM test cannot make.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlayerHeaderTest {

    @get:Rule
    val compose = createComposeRule()

    private val oldBook = nowPlaying("li_old", episodeId = null, title = "The old book", author = "First Writer")

    @Test
    fun `while another item starts, the header shows that item and not the loaded one`() {
        val header = playerHeader(
            now = oldBook,
            starting = StartingItem("li_new", null),
            details = StartingDetails("li_new", null, "The new book", "cover://li_new", 3_600.0),
        )

        assertThat(header.isStarting).isTrue()
        assertThat(header.title).isEqualTo("The new book")
        assertThat(header.coverUrl).isEqualTo("cover://li_new")
        assertThat(header.credit).isEqualTo(STARTING_LABEL)
        assertThat(header.startingDurationSec).isEqualTo(3_600.0)
        assertThat(header.titleLink).isEqualTo(PlayerLink.Item("li_new"))
        // The old author is not a link to follow while the new item is on screen.
        assertThat(header.authorLink).isNull()
    }

    @Test
    fun `before Room answers, the title is blank rather than the old item's`() {
        val header = playerHeader(oldBook, StartingItem("li_new", null), details = null)

        assertThat(header.isStarting).isTrue()
        assertThat(header.title).isEmpty()
        assertThat(header.coverUrl).isNull()
        assertThat(header.credit).isEqualTo(STARTING_LABEL)
    }

    @Test
    fun `details for an earlier request are not used for the current one`() {
        val header = playerHeader(
            now = oldBook,
            starting = StartingItem("li_newer", null),
            details = StartingDetails("li_new", null, "The new book", "cover://li_new", 60.0),
        )

        assertThat(header.title).isEmpty()
        assertThat(header.coverUrl).isNull()
    }

    @Test
    fun `a request for the loaded item shows the loaded item`() {
        val header = playerHeader(oldBook, StartingItem("li_old", null), details = null)

        assertThat(header.isStarting).isFalse()
        assertThat(header.title).isEqualTo("The old book")
        assertThat(header.credit).isEqualTo("First Writer")
    }

    @Test
    fun `another episode of the same show is another item`() {
        val episode = nowPlaying("li_show", episodeId = "ep_1", title = "Episode one", author = "A Host")

        assertThat(startsSomethingElse(episode, StartingItem("li_show", "ep_2"))).isTrue()
        assertThat(startsSomethingElse(episode, StartingItem("li_show", "ep_1"))).isFalse()
        assertThat(playerHeader(episode, StartingItem("li_show", "ep_2"), null).titleLink)
            .isEqualTo(PlayerLink.Episode("li_show", "ep_2"))
    }

    @Test
    fun `with nothing loaded, a starting item is still shown`() {
        val header = playerHeader(
            now = null,
            starting = StartingItem("li_new", null),
            details = StartingDetails("li_new", null, "The new book", null, 0.0),
        )

        assertThat(header.isStarting).isTrue()
        assertThat(header.title).isEqualTo("The new book")
    }

    @Test
    fun `with nothing loaded and nothing starting, the player says so`() {
        val header = playerHeader(now = null, starting = null, details = null)

        assertThat(header.isStarting).isFalse()
        assertThat(header.title).isEqualTo("Nothing playing")
        assertThat(header.titleLink).isNull()
    }

    @Test
    fun `once the request has loaded, the header is the loaded item again`() {
        val loaded = nowPlaying("li_new", null, title = "The new book", author = "Second Writer")

        val header = playerHeader(loaded, starting = null, details = null)

        assertThat(header.isStarting).isFalse()
        assertThat(header.credit).isEqualTo("Second Writer")
    }

    @Test
    fun `the mini player puts the starting label in the subtitle slot`() {
        compose.setContent {
            MaterialTheme {
                MiniPlayerBar(
                    title = "The new book",
                    subtitle = STARTING_LABEL,
                    coverUrl = null,
                    progress = 0f,
                    isPlaying = true,
                    onOpen = {},
                    onPlayPause = {},
                )
            }
        }

        compose.onNodeWithText("The new book").assertExists()
        compose.onNodeWithText(STARTING_LABEL).assertExists()
    }

    private fun nowPlaying(itemId: String, episodeId: String?, title: String, author: String?) = NowPlaying(
        libraryItemId = itemId,
        episodeId = episodeId,
        title = title,
        author = author,
        coverUrl = "cover://$itemId",
        durationSec = 600.0,
        tracks = emptyList(),
        chapters = emptyList(),
        ledgerId = "ledger",
        isTranscoded = false,
    )
}
