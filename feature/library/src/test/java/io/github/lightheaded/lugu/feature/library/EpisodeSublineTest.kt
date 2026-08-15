package io.github.lightheaded.lugu.feature.library

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.PodcastEpisode
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Test

/**
 * The secondary line is the only thing telling two episodes apart, so its edge cases —
 * a feed with no numbering, a date on the boundary between relative and absolute — are
 * worth pinning down rather than eyeballing on a device.
 */
class EpisodeSublineTest {

    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.of(2026, 3, 15)
    private val now = today.atTime(9, 30).atZone(zone).toInstant().toEpochMilli()

    private fun at(date: LocalDate, time: LocalTime = LocalTime.NOON): Long =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `today is named, not dated`() {
        assertThat(formatPublished(at(today), now, zone)).isEqualTo("Today")
    }

    @Test
    fun `late last night is yesterday, not today`() {
        val lastNight = at(today.minusDays(1), LocalTime.of(23, 55))
        assertThat(formatPublished(lastNight, now, zone)).isEqualTo("Yesterday")
    }

    @Test
    fun `the rest of the week counts back in days`() {
        assertThat(formatPublished(at(today.minusDays(3)), now, zone)).isEqualTo("3 days ago")
        assertThat(formatPublished(at(today.minusDays(6)), now, zone)).isEqualTo("6 days ago")
    }

    @Test
    fun `beyond a week becomes a date, with the year only when it differs`() {
        assertThat(formatPublished(at(today.minusDays(7)), now, zone)).isEqualTo("8 Mar")
        assertThat(formatPublished(at(LocalDate.of(2024, 3, 12)), now, zone)).isEqualTo("12 Mar 2024")
    }

    @Test
    fun `a feed with no date says nothing about dates`() {
        assertThat(formatPublished(0L, now, zone)).isNull()
    }

    @Test
    fun `numbering appears only as far as the feed supplies it`() {
        assertThat(formatEpisodeNumber("2", "14")).isEqualTo("S2 E14")
        assertThat(formatEpisodeNumber(null, "14")).isEqualTo("E14")
        assertThat(formatEpisodeNumber("2", null)).isEqualTo("S2")
        assertThat(formatEpisodeNumber(null, null)).isNull()
        assertThat(formatEpisodeNumber(" ", "")).isNull()
    }

    @Test
    fun `the line joins only the parts that exist`() {
        val full = PodcastEpisode(
            id = "e1",
            libraryItemId = "i1",
            title = "Lighthouse Wakes",
            season = "2",
            episodeNumber = "14",
            publishedAtMs = at(LocalDate.of(2026, 2, 12)),
            durationSec = 2880.0,
        )
        assertThat(episodeSubline(full, now, zone)).isEqualTo("S2 E14 · 12 Feb · 48m")

        val bare = PodcastEpisode(id = "e2", libraryItemId = "i1", title = "Untitled")
        assertThat(episodeSubline(bare, now, zone)).isEmpty()
    }

    @Test
    fun `the count line names a total only when nothing is hidden`() {
        assertThat(episodeCountLine(48, 1204)).isEqualTo("48 of 1,204 episodes")
        assertThat(episodeCountLine(1204, 1204)).isEqualTo("1,204 episodes")
        assertThat(episodeCountLine(1, 1)).isEqualTo("1 episode")
    }
}
