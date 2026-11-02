package io.github.lightheaded.lugu.feature.player

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.playback.PlaybackEvents
import io.github.lightheaded.lugu.playback.PositionJump
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test

class PositionHistoryRowsTest {

    private val zone = ZoneId.of("Europe/Tallinn")
    private val today = LocalDate.of(2026, 11, 2)

    private fun at(day: LocalDate, hour: Int, minute: Int): Long =
        LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `a play and a pause show the time and the position they happened at`() {
        val play = historyRow(PositionJump(600.0, 600.0, at(today, 21, 5), PlaybackEvents.PLAY), zone)
        val pause = historyRow(PositionJump(1_800.0, 1_800.0, at(today, 23, 40), PlaybackEvents.PAUSE_SLEEP_TIMER), zone)

        assertThat(play.time).isEqualTo("21:05")
        assertThat(play.kind).isEqualTo(HistoryKind.PLAY)
        assertThat(play.title).isEqualTo("Played from 10:00")
        assertThat(play.restoreSec).isEqualTo(600.0)

        assertThat(pause.time).isEqualTo("23:40")
        assertThat(pause.kind).isEqualTo(HistoryKind.PAUSE)
        assertThat(pause.title).isEqualTo("Paused at 30:00")
        assertThat(pause.detail).isEqualTo("Sleep timer")
    }

    @Test
    fun `a jump says why it happened and goes back to where it started`() {
        val row = historyRow(PositionJump(3_600.0, 0.0, at(today, 8, 0), "chapter-previous"), zone)

        assertThat(row.kind).isEqualTo(HistoryKind.JUMP)
        assertThat(row.title).isEqualTo("1:00:00 → 0:00")
        assertThat(row.detail).isEqualTo("Previous chapter")
        assertThat(row.restoreSec).isEqualTo(3_600.0)
    }

    @Test
    fun `entries group by day, newest day first`() {
        val history = listOf(
            PositionJump(10.0, 10.0, at(today.minusDays(1), 22, 0), PlaybackEvents.PAUSE),
            PositionJump(20.0, 20.0, at(today, 7, 30), PlaybackEvents.PLAY),
            PositionJump(5.0, 5.0, at(LocalDate.of(2026, 10, 26), 12, 0), PlaybackEvents.PLAY),
            PositionJump(1.0, 1.0, at(LocalDate.of(2025, 12, 31), 12, 0), PlaybackEvents.PLAY),
        )

        val days = historyDays(history, zone, today)

        assertThat(days.map { it.label })
            .containsExactly("Today", "Yesterday", "Mon 26 Oct", "Wed 31 Dec 2025")
            .inOrder()
        assertThat(days.first().rows.single().title).isEqualTo("Played from 0:20")
    }

    @Test
    fun `a pause by the device names its cause, and a plain one names none`() {
        val noisy = historyRow(PositionJump(1.0, 1.0, 0, PlaybackEvents.PAUSE_NOISY), zone)
        val plain = historyRow(PositionJump(1.0, 1.0, 0, PlaybackEvents.PAUSE), zone)

        assertThat(noisy.detail).isEqualTo("Headphones disconnected")
        assertThat(plain.detail).isNull()
    }
}
