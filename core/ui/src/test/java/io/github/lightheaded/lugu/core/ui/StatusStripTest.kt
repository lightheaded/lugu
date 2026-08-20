package io.github.lightheaded.lugu.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The status line, and the flicker it exists to prevent.
 *
 * What is asserted here is timing, because timing is the whole complaint: a spinner that
 * appeared and vanished inside half a second said nothing and drew the eye anyway. The
 * clock is driven by hand so that "a sync that took 200ms" is a fact the test states rather
 * than a race it hopes for.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StatusStripTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `work that finishes quickly is never mentioned`() {
        var status by mutableStateOf<Status?>(Status.Working("Syncing Audiobooks"))
        compose.mainClock.autoAdvance = false
        compose.setContent { StatusStrip(status = status, onDismiss = {}) }

        // Shorter than a sync of an already-mirrored library, which is the case that had
        // been twitching the top bar on every launch.
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("Syncing Audiobooks").assertDoesNotExist()

        status = null
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Syncing Audiobooks").assertDoesNotExist()
    }

    @Test
    fun `work that goes on says what it is doing`() {
        val status = mutableStateOf<Status?>(Status.Working("Syncing Audiobooks — 40 of 400", 0.1f))
        compose.mainClock.autoAdvance = false
        compose.setContent { StatusStrip(status = status.value, onDismiss = {}) }

        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Syncing Audiobooks — 40 of 400").assertIsDisplayed()
    }

    /**
     * A bar that disappears the instant it fills reads as a glitch, so it is held for a
     * moment — and then it does go, which is the half worth pinning down.
     */
    @Test
    fun `the line stays a moment after the work ends and then goes`() {
        var status by mutableStateOf<Status?>(Status.Working("Syncing where you got to"))
        compose.mainClock.autoAdvance = false
        compose.setContent { StatusStrip(status = status, onDismiss = {}) }

        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Syncing where you got to").assertIsDisplayed()

        status = null
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("Syncing where you got to").assertIsDisplayed()

        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Syncing where you got to").assertDoesNotExist()
    }

    /**
     * A confirmation is a reply to something just pressed. Delaying it the way work is
     * delayed would attach it to whatever the person did next.
     */
    @Test
    fun `a confirmation arrives at once and takes itself away`() {
        var dismissed = false
        compose.mainClock.autoAdvance = false
        compose.setContent {
            StatusStrip(status = Status.Done("Marked 3 items"), onDismiss = { dismissed = true })
        }

        compose.mainClock.advanceTimeBy(16)
        compose.onNodeWithText("Marked 3 items").assertIsDisplayed()
        assertThat(dismissed).isFalse()

        compose.mainClock.advanceTimeBy(5_000)
        assertThat(dismissed).isTrue()
    }

    /** The one kind that may not disappear on its own, because it is still true. */
    @Test
    fun `a problem waits to be dismissed`() {
        var dismissed = false
        compose.mainClock.autoAdvance = false
        compose.setContent {
            StatusStrip(
                status = Status.Problem("Could not reach the server"),
                onDismiss = { dismissed = true },
            )
        }

        compose.mainClock.advanceTimeBy(30_000)
        compose.onNodeWithText("Could not reach the server").assertIsDisplayed()
        assertThat(dismissed).isFalse()

        compose.onNodeWithText("Could not reach the server").performClick()
        assertThat(dismissed).isTrue()
    }

    /**
     * A note is a fact rather than an event, so none of the timing above applies to it: it
     * is drawn at once and it is still there a minute later.
     */
    @Test
    fun `a note arrives at once and stays`() {
        var dismissed = false
        compose.mainClock.autoAdvance = false
        compose.setContent {
            StatusStrip(
                status = Status.Note("Transcoding — seeking is less precise"),
                onDismiss = { dismissed = true },
            )
        }

        compose.mainClock.advanceTimeBy(16)
        compose.onNodeWithText("Transcoding — seeking is less precise").assertIsDisplayed()

        compose.mainClock.advanceTimeBy(60_000)
        compose.onNodeWithText("Transcoding — seeking is less precise").assertIsDisplayed()
        assertThat(dismissed).isFalse()
    }

    /**
     * The two kinds that stay until something ends them are the two that can be put away by
     * hand. A fact that has been read once does not need to keep the top of the screen.
     */
    @Test
    fun `a note can be put away by hand`() {
        var dismissed = false
        compose.mainClock.autoAdvance = false
        compose.setContent {
            StatusStrip(
                status = Status.Note("An Audiobookshelf server answered"),
                onDismiss = { dismissed = true },
            )
        }

        compose.mainClock.advanceTimeBy(16)
        compose.onNodeWithText("An Audiobookshelf server answered").performClick()
        assertThat(dismissed).isTrue()
    }

    /**
     * Work is named by the app in a few words and a confirmation carries the server's own,
     * so only one of the two may wrap. Neither moves anything either way — this is an
     * overlay — but a sync line that grew to four lines would cover the content it is
     * drawn over.
     */
    @Test
    fun `work keeps to one line and an outcome may wrap`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Column(Modifier.width(200.dp)) {
                StatusStrip(status = Status.Working(LONG_TEXT), onDismiss = {})
                StatusStrip(status = Status.Problem(LONG_TEXT), onDismiss = {})
            }
        }

        compose.mainClock.advanceTimeBy(1_000)
        val strips = compose.onAllNodesWithText(LONG_TEXT, substring = true)
        val working = strips[0].fetchSemanticsNode().size.height
        val problem = strips[1].fetchSemanticsNode().size.height
        assertThat(problem).isGreaterThan(working)
    }

    private companion object {
        /** Long enough to need more than one line in a narrow strip, and no library data. */
        const val LONG_TEXT = "Nothing answered within the deadline lugu uses, and the " +
            "reason given was that the connection was refused"
    }
}
