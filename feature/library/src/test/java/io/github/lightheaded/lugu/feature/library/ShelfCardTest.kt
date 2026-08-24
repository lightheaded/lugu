package io.github.lightheaded.lugu.feature.library

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.LibraryItem
import io.github.lightheaded.lugu.core.model.MediaProgress
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.sync.ShelfEntry
import org.junit.Test

/**
 * What a shelf card says about itself, and what the player says about it.
 *
 * Both of these are read at a glance in the screenshots and neither is proved by them: a
 * picture cannot show that the remaining time came from the episode rather than from the
 * feed, and it cannot show that a second episode of the same show is not also claiming to
 * be playing.
 */
class ShelfCardTest {

    private val podcast = LibraryItem(
        id = "pod",
        libraryId = "lib",
        mediaType = MediaType.PODCAST,
        title = "Coastal Signal",
        authorName = "Coastal Signal Collective",
        // A feed has no duration worth reading, and the mirror stores what the server sent.
        durationSec = 1_080_000.0,
    )

    private val book = LibraryItem(
        id = "book",
        libraryId = "lib",
        mediaType = MediaType.BOOK,
        title = "The Lighthouse Wakes",
        authorName = "James T. R. Corven",
        durationSec = 3_600.0,
    )

    private fun episodeCard(episodeId: String, fraction: Double) = ShelfCard(
        ShelfEntry(
            item = podcast,
            episodeId = episodeId,
            episodeTitle = "Episode $episodeId",
            playedDurationSec = 3_000.0,
        ),
        MediaProgress(libraryItemId = "pod", episodeId = episodeId, progress = fraction),
    )

    @Test
    fun `an episode is titled by the episode and placed by the show`() {
        val card = episodeCard("e1", 0.5)

        assertThat(card.title).isEqualTo("Episode e1")
        assertThat(card.secondary).isEqualTo("Coastal Signal")
    }

    @Test
    fun `a book is titled by the book and placed by its author`() {
        val card = ShelfCard(
            ShelfEntry(book, playedDurationSec = book.durationSec),
            MediaProgress(libraryItemId = "book", progress = 0.25),
        )

        assertThat(card.title).isEqualTo("The Lighthouse Wakes")
        assertThat(card.secondary).isEqualTo("James T. R. Corven")
    }

    @Test
    fun `what is left is measured against the episode, not the feed`() {
        // Half of a 50-minute episode, not half of the three hundred hours behind it.
        assertThat(episodeCard("e1", 0.5).remainingSec).isEqualTo(1_500.0)
    }

    @Test
    fun `only the episode that is loaded reports itself as playing`() {
        val playing = PlayingNow(itemId = "pod", episodeId = "e1", isPlaying = true)

        assertThat(playing.isLoaded(episodeCard("e1", 0.5))).isTrue()
        assertThat(playing.isLoaded(episodeCard("e2", 0.1))).isFalse()
    }

    @Test
    fun `a book is not the episode of a podcast that happens to share an id`() {
        val playing = PlayingNow(itemId = "pod", episodeId = null, isPlaying = true)

        assertThat(playing.isLoaded(episodeCard("e1", 0.5))).isFalse()
    }

    @Test
    fun `a started thing resumes on tap and an untouched thing does not`() {
        // The same rule draws the play badge, so this is also the test that the badge
        // appears exactly on the cards whose tap starts audio.
        val started = ShelfCard(
            ShelfEntry(book, playedDurationSec = book.durationSec),
            MediaProgress(libraryItemId = "book", progress = 0.25),
        )
        val untouched = ShelfCard(ShelfEntry(book, playedDurationSec = book.durationSec), null)

        assertThat(started.tapResumes).isTrue()
        assertThat(untouched.tapResumes).isFalse()
    }
}
