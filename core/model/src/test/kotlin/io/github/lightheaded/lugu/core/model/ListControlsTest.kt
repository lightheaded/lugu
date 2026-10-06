package io.github.lightheaded.lugu.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The policy behind every sorted or filtered list.
 *
 * These rules are shared by the library grid, the episode list and the downloads screen
 * precisely so the three cannot disagree about what "in progress" means — which makes
 * them worth pinning down here rather than in each screen's own tests.
 */
class ListControlsTest {

    private fun facts(
        title: String = "Title",
        secondary: String? = null,
        addedAtMs: Long = 0,
        publishedAtMs: Long = 0,
        durationSec: Double = 0.0,
        progress: Float = 0f,
        isFinished: Boolean = false,
        isDownloaded: Boolean = false,
        lastPlayedAtMs: Long = 0,
    ) = ListFacts(
        title = title,
        secondary = secondary,
        addedAtMs = addedAtMs,
        publishedAtMs = publishedAtMs,
        durationSec = durationSec,
        progressFraction = progress,
        isFinished = isFinished,
        isDownloaded = isDownloaded,
        lastPlayedAtMs = lastPlayedAtMs,
    )

    @Test
    fun `a finished item is not also in progress`() {
        val finished = facts(progress = 1f, isFinished = true)

        assertThat(ListControls.matches(finished, ListFilter.FINISHED)).isTrue()
        assertThat(ListControls.matches(finished, ListFilter.IN_PROGRESS)).isFalse()
        // Nor unplayed: it was started, and finishing it does not undo that.
        assertThat(ListControls.matches(finished, ListFilter.UNPLAYED)).isFalse()
    }

    @Test
    fun `not finished keeps what is unstarted and what is in progress, and nothing finished`() {
        assertThat(ListControls.matches(facts(progress = 0f), ListFilter.NOT_FINISHED)).isTrue()
        assertThat(ListControls.matches(facts(progress = 0.4f), ListFilter.NOT_FINISHED)).isTrue()
        // Finished by the flag, not by the position: the web can mark a book finished at 0.
        assertThat(ListControls.matches(facts(progress = 0f, isFinished = true), ListFilter.NOT_FINISHED))
            .isFalse()
    }

    @Test
    fun `an item finished without ever reporting progress still counts as started`() {
        // Marking something finished on the web leaves no position behind, so a naive
        // "progress is zero means unstarted" would put it back on the unplayed shelf.
        val marked = facts(progress = 0f, isFinished = true)

        assertThat(ListControls.matches(marked, ListFilter.UNPLAYED)).isFalse()
        assertThat(ListControls.matches(marked, ListFilter.FINISHED)).isTrue()
    }

    @Test
    fun `search matches the secondary line as well as the title`() {
        val row = facts(title = "Lighthouse Wakes", secondary = "Jefferson Vale")

        assertThat(ListControls.matches(row, "vale")).isTrue()
        assertThat(ListControls.matches(row, "WAKES")).isTrue()
        assertThat(ListControls.matches(row, "  ")).isTrue()
        assertThat(ListControls.matches(row, "corven")).isFalse()
    }

    @Test
    fun `sorting by author puts the unattributed last rather than first`() {
        val rows = listOf(
            facts(title = "B", secondary = null),
            facts(title = "A", secondary = "Vale"),
            facts(title = "C", secondary = "Corven"),
        )

        val sorted = ListControls.sortItems(rows, ItemSort.AUTHOR) { it }

        assertThat(sorted.map { it.title }).containsExactly("C", "A", "B").inOrder()
    }

    @Test
    fun `episodes default to newest first`() {
        val rows = listOf(
            facts(title = "old", publishedAtMs = 100),
            facts(title = "new", publishedAtMs = 300),
            facts(title = "middle", publishedAtMs = 200),
        )

        val sorted = ListControls.sortEpisodes(rows, EpisodeSort.NEWEST) { it }

        assertThat(sorted.map { it.title }).containsExactly("new", "middle", "old").inOrder()
    }

    @Test
    fun `episodes played most recently come first, and the never-played tail is newest first`() {
        val rows = listOf(
            facts(title = "Old, unplayed", publishedAtMs = 100),
            facts(title = "Played yesterday", publishedAtMs = 50, lastPlayedAtMs = 2_000),
            facts(title = "New, unplayed", publishedAtMs = 300),
            facts(title = "Played today", publishedAtMs = 10, lastPlayedAtMs = 3_000),
        )

        val sorted = ListControls.sortEpisodes(rows, EpisodeSort.RECENTLY_PLAYED) { it }

        assertThat(sorted.map { it.title })
            .containsExactly("Played today", "Played yesterday", "New, unplayed", "Old, unplayed")
            .inOrder()
    }

    @Test
    fun `podcasts with the newest episode come first, and unknown dates last`() {
        // Zero means lugu has not fetched the episodes yet. Ranking that as the oldest
        // possible date is correct, but the unknown tail still needs an order a person can
        // scan, so the title breaks the tie.
        val rows = listOf(
            facts(title = "Unknown B", publishedAtMs = 0),
            facts(title = "Stale", publishedAtMs = 100),
            facts(title = "Unknown A", publishedAtMs = 0),
            facts(title = "Fresh", publishedAtMs = 300),
        )

        val sorted = ListControls.sortItems(rows, ItemSort.LATEST_EPISODE) { it }

        assertThat(sorted.map { it.title })
            .containsExactly("Fresh", "Stale", "Unknown A", "Unknown B")
            .inOrder()
    }

    @Test
    fun `the most recently played comes first, and the never played last by title`() {
        // A finished item keeps its place: it was listened to then, and the sort answers
        // "what did I listen to", not "what is left".
        val rows = listOf(
            facts(title = "Never 10", lastPlayedAtMs = 0),
            facts(title = "Last week", lastPlayedAtMs = 100),
            facts(title = "Never 2", lastPlayedAtMs = 0),
            facts(title = "Finished today", lastPlayedAtMs = 300, isFinished = true),
            facts(title = "Yesterday", lastPlayedAtMs = 200),
        )

        val sorted = ListControls.sortItems(rows, ItemSort.RECENTLY_PLAYED) { it }

        assertThat(sorted.map { it.title })
            .containsExactly("Finished today", "Yesterday", "Last week", "Never 2", "Never 10")
            .inOrder()
    }

    /**
     * The bug this exists to prevent: lexicographic ordering compares one character at a
     * time, so "10" sorts before "2" and a series is listed in an order that recommends
     * book ten to somebody who has just finished book one.
     */
    @Test
    fun `numbers in a title are compared as numbers`() {
        val rows = listOf(
            facts(title = "Breakwater Book 10"),
            facts(title = "Breakwater Book 2"),
            facts(title = "Breakwater Book 1"),
        )

        val sorted = ListControls.sortItems(rows, ItemSort.TITLE) { it }

        assertThat(sorted.map { it.title })
            .containsExactly("Breakwater Book 1", "Breakwater Book 2", "Breakwater Book 10")
            .inOrder()
    }

    @Test
    fun `leading zeros do not change the order`() {
        assertThat(ListControls.naturalCompare("Chapter 07", "Chapter 7")).isEqualTo(0)
        assertThat(ListControls.naturalCompare("Chapter 0007", "Chapter 1")).isGreaterThan(0)
    }

    @Test
    fun `natural ordering is still case-insensitive and still orders plain text`() {
        assertThat(ListControls.naturalCompare("apple", "Apple")).isEqualTo(0)
        assertThat(ListControls.naturalCompare("Anvil", "Bell")).isLessThan(0)
        // A prefix sorts before the longer string that contains it.
        assertThat(ListControls.naturalCompare("Book", "Book 2")).isLessThan(0)
    }

    @Test
    fun `a number is compared against the text at the same position`() {
        // "2" against "b": neither run is a pair of digits, so this falls through to the
        // character comparison rather than silently treating the digit as zero.
        assertThat(ListControls.naturalCompare("Part 2", "Part b")).isLessThan(0)
    }

    @Test
    fun `an unknown stored id falls back rather than throwing`() {
        // Sort choices are persisted by id, so a renamed or removed option must degrade
        // to the default instead of taking the app down on the next launch.
        assertThat(ItemSort.fromId("nonsense")).isEqualTo(ItemSort.TITLE)
        assertThat(EpisodeSort.fromId(null)).isEqualTo(EpisodeSort.NEWEST)
        assertThat(ListFilter.fromId("")).isEqualTo(ListFilter.ALL)
    }
}
