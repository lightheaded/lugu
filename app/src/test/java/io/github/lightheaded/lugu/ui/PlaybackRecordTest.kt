package io.github.lightheaded.lugu.ui

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.sync.DiaryEntry
import io.github.lightheaded.lugu.core.sync.PlaybackDiary
import io.github.lightheaded.lugu.core.sync.PlaybackEvent
import org.junit.Test

/**
 * The summariser is the part of the diagnosis that a reader will believe without
 * checking, so it is the part that has to be right. Timestamps are all derived from
 * [PlaybackRecord.startOfDay] so the tests say the same thing in every time zone.
 */
class PlaybackRecordTest {

    private val today = PlaybackRecord.startOfDay(1_755_000_000_000)

    private fun at(hour: Int, minute: Int = 0, dayOffset: Int = 0): Long =
        today + dayOffset * DAY_MS + hour * 3_600_000L + minute * 60_000L

    @Test
    fun `a restart while playing counts as the app being killed`() {
        val entries = listOf(
            DiaryEntry(at(9), PlaybackDiary.PROCESS_STARTED),
            DiaryEntry(at(9, 1), PlaybackEvent.PLAY_REQUESTED),
            DiaryEntry(at(9, 2), PlaybackEvent.PLAYING),
            DiaryEntry(at(10), PlaybackDiary.PROCESS_STARTED),
        )

        val summary = PlaybackRecord.summarise(entries, today)

        assertThat(summary.killedWhilePlaying).isEqualTo(1)
        assertThat(summary.total).isEqualTo(1)
    }

    @Test
    fun `a restart after a pause is not a stop at all`() {
        val entries = listOf(
            DiaryEntry(at(9), PlaybackEvent.PLAYING),
            DiaryEntry(at(9, 30), PlaybackEvent.PAUSED),
            DiaryEntry(at(10), PlaybackDiary.PROCESS_STARTED),
        )

        assertThat(PlaybackRecord.summarise(entries, today).total).isEqualTo(0)
    }

    @Test
    fun `the very first process start has nothing before it to judge`() {
        val entries = listOf(DiaryEntry(at(9), PlaybackDiary.PROCESS_STARTED))

        assertThat(PlaybackRecord.summarise(entries, today).total).isEqualTo(0)
    }

    @Test
    fun `each recorded cause is counted separately`() {
        val entries = listOf(
            DiaryEntry(at(8), PlaybackEvent.PLAYING),
            DiaryEntry(at(9), PlaybackDiary.PROCESS_STARTED),
            DiaryEntry(at(10), PlaybackEvent.ERROR, "network"),
            DiaryEntry(at(11), PlaybackEvent.SUPPRESSED),
            DiaryEntry(at(12), PlaybackEvent.SLEEP_TIMER_FIRED),
            DiaryEntry(at(13), PlaybackEvent.TASK_REMOVED),
            DiaryEntry(at(14), PlaybackEvent.UNEXPECTED_STOP),
        )

        val summary = PlaybackRecord.summarise(entries, today)

        assertThat(summary.killedWhilePlaying).isEqualTo(1)
        assertThat(summary.playerErrors).isEqualTo(1)
        assertThat(summary.suppressions).isEqualTo(1)
        assertThat(summary.sleepTimerStops).isEqualTo(1)
        assertThat(summary.swipedAway).isEqualTo(1)
        assertThat(summary.unexplainedStops).isEqualTo(1)
        assertThat(summary.total).isEqualTo(6)
    }

    @Test
    fun `events before the period are not counted`() {
        val entries = listOf(
            DiaryEntry(at(20, dayOffset = -1), PlaybackEvent.ERROR),
            DiaryEntry(at(10), PlaybackEvent.ERROR),
        )

        assertThat(PlaybackRecord.summarise(entries, today).playerErrors).isEqualTo(1)
    }

    @Test
    fun `a kill spanning midnight still reads the entry before it`() {
        val entries = listOf(
            DiaryEntry(at(23, dayOffset = -1), PlaybackEvent.PLAYING),
            DiaryEntry(at(7), PlaybackDiary.PROCESS_STARTED),
        )

        assertThat(PlaybackRecord.summarise(entries, today).killedWhilePlaying).isEqualTo(1)
    }

    @Test
    fun `one cause does not have its count said twice`() {
        val summary = PlaybackRecordSummary(killedWhilePlaying = 2)

        assertThat(summary.sentence("today"))
            .isEqualTo("Playback stopped twice today, after the app was killed.")
    }

    @Test
    fun `several causes are listed with their counts`() {
        val summary = PlaybackRecordSummary(killedWhilePlaying = 2, playerErrors = 1)

        assertThat(summary.sentence("today")).isEqualTo(
            "Playback stopped 3 times today: twice after the app was killed and " +
                "once on a player error.",
        )
    }

    @Test
    fun `nothing to report says nothing`() {
        assertThat(PlaybackRecordSummary().sentence("today")).isNull()
    }

    @Test
    fun `the record is grouped newest day first with readable headings`() {
        val entries = listOf(
            DiaryEntry(at(9, dayOffset = -2), PlaybackEvent.PLAYING),
            DiaryEntry(at(9, dayOffset = -1), PlaybackEvent.PLAYING),
            DiaryEntry(at(9), PlaybackEvent.PLAYING),
            DiaryEntry(at(10), PlaybackEvent.PAUSED),
        )

        val days = PlaybackRecord.read(entries, today + 12 * 3_600_000L)

        assertThat(days).hasSize(3)
        assertThat(days[0].label).isEqualTo("Today")
        assertThat(days[1].label).isEqualTo("Yesterday")
        // Anything older falls back to its date, so it must not be one of the two words.
        assertThat(days[2].label).isNotEqualTo("Yesterday")
        assertThat(days[2].label).isNotEqualTo("Today")
        assertThat(days[0].lines.map { it.entry.event })
            .containsExactly(PlaybackEvent.PAUSED, PlaybackEvent.PLAYING).inOrder()
    }

    @Test
    fun `the lines that need reading get a note and the rest do not`() {
        val entries = listOf(
            DiaryEntry(at(9), PlaybackEvent.PLAYING),
            DiaryEntry(at(10), PlaybackDiary.PROCESS_STARTED),
            DiaryEntry(at(11), PlaybackEvent.SUPPRESSED),
            DiaryEntry(at(12), PlaybackEvent.PAUSED),
        )

        assertThat(PlaybackRecord.note(entries, 0)).isNull()
        assertThat(PlaybackRecord.note(entries, 1)).contains("without warning")
        assertThat(PlaybackRecord.note(entries, 2)).contains("took the audio")
        assertThat(PlaybackRecord.note(entries, 3)).isNull()
    }

    private companion object {
        const val DAY_MS = 24 * 3_600_000L
    }
}
