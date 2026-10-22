package io.github.lightheaded.lugu.core.download

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.sync.NewEpisode
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/** The words on a new-episode row: the snippet cutting and the date. */
class DescriptionSnippetTest {

    @Test
    fun `markup is gone and line breaks become spaces`() {
        val snippet = descriptionSnippet(
            "<p>Tide tables, <b>read aloud</b>.</p><p>Then a long &amp; slow walk.</p>",
        )

        assertThat(snippet).isEqualTo("Tide tables, read aloud. Then a long & slow walk.")
    }

    @Test
    fun `a short text is kept whole, with no ellipsis`() {
        assertThat(descriptionSnippet("Four minutes on fog horns.", maxChars = 40))
            .isEqualTo("Four minutes on fog horns.")
    }

    @Test
    fun `a long text is cut at a word boundary and never exceeds the limit`() {
        val text = "The lighthouse keeper explains the rota, the lamp, and the long nights alone"
        val snippet = descriptionSnippet(text, maxChars = 40)!!

        assertThat(snippet).isEqualTo("The lighthouse keeper explains the rota…")
        assertThat(snippet.length).isAtMost(40)
    }

    @Test
    fun `punctuation before the cut is trimmed`() {
        val snippet = descriptionSnippet("One, two, three, four, five, six, seven", maxChars = 18)

        assertThat(snippet).isEqualTo("One, two, three…")
    }

    @Test
    fun `one word too long to fit is cut where it must be`() {
        val snippet = descriptionSnippet("a ${"x".repeat(60)}", maxChars = 20)!!

        assertThat(snippet).isEqualTo("a ${"x".repeat(17)}…")
        assertThat(snippet.length).isEqualTo(20)
    }

    @Test
    fun `a hard cut never splits a surrogate pair`() {
        // Each wave is two chars; an odd limit would land in the middle of one.
        val snippet = descriptionSnippet("🌊".repeat(20), maxChars = 8)!!

        assertThat(snippet).isEqualTo("🌊".repeat(3) + "…")
    }

    @Test
    fun `nothing there gives no snippet`() {
        assertThat(descriptionSnippet(null)).isNull()
        assertThat(descriptionSnippet("   ")).isNull()
        assertThat(descriptionSnippet("<p> </p>")).isNull()
    }

    @Test
    fun `the row shows the date, then the snippet`() {
        val content = rowContent(episode(publishedAtMs = MAR_9_2024), "<p>Fog.</p>", Locale.US, UTC)

        assertThat(content.title).isEqualTo("A quiet hour on 500 kHz")
        assertThat(content.podcast).isEqualTo("Coastal Signal")
        assertThat(content.text).isEqualTo("Mar 9, 2024 · Fog.")
        assertThat(content.expanded).isEqualTo("Mar 9, 2024\nFog.")
    }

    @Test
    fun `no date and no description falls back to the podcast name`() {
        val content = rowContent(episode(publishedAtMs = 0L), null, Locale.US, UTC)

        assertThat(content.text).isEqualTo("Coastal Signal")
    }

    @Test
    fun `the date is the day in the reader's zone`() {
        // 23:30 UTC is already the next day two hours east.
        val lateEvening = 1_710_027_000_000L
        assertThat(publishedDate(lateEvening, Locale.US, UTC)).isEqualTo("Mar 9, 2024")
        assertThat(publishedDate(lateEvening, Locale.US, TimeZone.getTimeZone("GMT+02:00")))
            .isEqualTo("Mar 10, 2024")
    }

    @Test
    fun `the same episode always gets the same row id`() {
        val a = episode(publishedAtMs = 1L)
        assertThat(notificationIdOf(a)).isEqualTo(notificationIdOf(a.copy(publishedAtMs = 2L)))
        assertThat(notificationIdOf(a)).isNotEqualTo(notificationIdOf(a.copy(episodeId = "ep_15")))
        assertThat(notificationIdOf(a)).isNotEqualTo(NewEpisodeNotifier.SUMMARY_ID)
    }

    private fun episode(publishedAtMs: Long) = NewEpisode(
        libraryItemId = "li_pod",
        episodeId = "ep_14",
        podcastTitle = "Coastal Signal",
        episodeTitle = "A quiet hour on 500 kHz",
        publishedAtMs = publishedAtMs,
    )

    private companion object {
        const val MAR_9_2024 = 1_710_000_000_000L
        val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    }
}
