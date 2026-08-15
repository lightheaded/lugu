package io.github.lightheaded.lugu.ui

import io.github.lightheaded.lugu.core.sync.DiaryEntry
import io.github.lightheaded.lugu.core.sync.PlaybackDiary
import io.github.lightheaded.lugu.core.sync.PlaybackEvent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * How many times playback stopped, and for which of the reasons the diary can tell apart.
 *
 * Counting is separated from the screen because it is the part that can be wrong in a way
 * nobody notices: a summary that miscounts is worse than no summary, since it will be
 * believed. Every field here maps to something [PlaybackDiary] actually records — there is
 * no category derived from guesswork, and no "unknown" bucket that quietly absorbs events
 * the diary does not understand.
 */
data class PlaybackRecordSummary(
    val killedWhilePlaying: Int = 0,
    val playerErrors: Int = 0,
    val suppressions: Int = 0,
    val sleepTimerStops: Int = 0,
    val swipedAway: Int = 0,
    val unexplainedStops: Int = 0,
) {
    val total: Int
        get() = killedWhilePlaying + playerErrors + suppressions +
            sleepTimerStops + swipedAway + unexplainedStops

    /**
     * The summary as one sentence, or null when nothing interrupted playback.
     *
     * A sentence rather than a row of counters: the question being answered is "did it
     * crash, or did it just stop", and a number next to a label does not answer it.
     */
    fun sentence(period: String): String? {
        if (total == 0) return null
        val causes = buildList {
            if (killedWhilePlaying > 0) add(killedWhilePlaying to "after the app was killed")
            if (playerErrors > 0) add(playerErrors to "on a player error")
            if (suppressions > 0) add(suppressions to "when something else took the audio")
            if (sleepTimerStops > 0) add(sleepTimerStops to "on the sleep timer")
            if (swipedAway > 0) add(swipedAway to "when the app was swiped away")
            if (unexplainedStops > 0) add(unexplainedStops to "with no reason recorded")
        }
        // With one cause the count has already been given, and repeating it — "stopped
        // twice today: twice after the app was killed" — reads as though it happened four
        // times.
        if (causes.size == 1) return "Playback stopped ${times(total)} $period, ${causes.first().second}."
        val listed = causes.map { (count, phrase) -> "${times(count)} $phrase" }
        return "Playback stopped ${times(total)} $period: ${listed.joinList()}."
    }

    private fun times(count: Int): String = when (count) {
        1 -> "once"
        2 -> "twice"
        else -> "$count times"
    }

    private fun List<String>.joinList(): String = when (size) {
        1 -> first()
        else -> dropLast(1).joinToString(", ") + " and " + last()
    }
}

/**
 * One line of the record, with the reading of it where the line alone does not say.
 *
 * The note is the whole point of this screen. "process started" and "playback suppressed"
 * are accurate and mean nothing to the person who just lost their audiobook in the car.
 */
data class RecordLine(val entry: DiaryEntry, val note: String? = null)

/** A day's worth of the record, newest line first, under the heading it is shown below. */
data class DiaryDay(val label: String, val lines: List<RecordLine>)

/**
 * Reads the diary the way a person would.
 *
 * The two diagnoses that matter are not written down as such by the diary, because the
 * diary only records what happened. They have to be recognised here:
 *
 *  - **The process was killed.** Nothing can write "I was killed" from a process that is
 *    being killed, so the evidence is the shape of the record rather than any one line: a
 *    fresh `process started` whose previous entry was playback in progress. A stop the app
 *    chose would have written `paused`, `ended` or `playback service destroyed` first.
 *  - **Playback was suppressed.** Android reports audio focus loss and an unsuitable
 *    output route through the same suppression signal, so the diary records one event for
 *    both; the summary says what that means rather than repeating the word.
 */
object PlaybackRecord {

    /**
     * Counts stops that happened at or after [sinceMs].
     *
     * The whole list is walked even so, because recognising a killed process needs the
     * entry *before* the restart, and that one is often on the previous day.
     */
    fun summarise(entries: List<DiaryEntry>, sinceMs: Long): PlaybackRecordSummary {
        var killed = 0
        var errors = 0
        var suppressed = 0
        var sleepTimer = 0
        var swiped = 0
        var unexplained = 0

        entries.forEachIndexed { index, entry ->
            if (entry.atMs < sinceMs) return@forEachIndexed
            when (entry.event) {
                PlaybackDiary.PROCESS_STARTED -> {
                    val previous = entries.getOrNull(index - 1) ?: return@forEachIndexed
                    if (previous.event in IN_PROGRESS) killed++
                }
                PlaybackEvent.ERROR -> errors++
                PlaybackEvent.SUPPRESSED -> suppressed++
                PlaybackEvent.SLEEP_TIMER_FIRED -> sleepTimer++
                PlaybackEvent.TASK_REMOVED -> swiped++
                PlaybackEvent.UNEXPECTED_STOP -> unexplained++
            }
        }

        return PlaybackRecordSummary(
            killedWhilePlaying = killed,
            playerErrors = errors,
            suppressions = suppressed,
            sleepTimerStops = sleepTimer,
            swipedAway = swiped,
            unexplainedStops = unexplained,
        )
    }

    /**
     * The record as it is read: annotated, grouped into days, newest first, with the lines
     * inside each day newest first too. The diary stores oldest-first because that is how
     * a log is written; this screen is read the other way round, because the question is
     * always about the most recent stop.
     */
    fun read(entries: List<DiaryEntry>, nowMs: Long): List<DiaryDay> =
        entries
            .mapIndexed { index, entry -> RecordLine(entry, note(entries, index)) }
            .groupBy { startOfDay(it.entry.atMs) }
            .entries
            .sortedByDescending { it.key }
            .map { (dayStart, ofThatDay) ->
                DiaryDay(
                    label = dayLabel(dayStart, nowMs),
                    lines = ofThatDay.sortedByDescending { it.entry.atMs },
                )
            }

    /**
     * What a line means, where it means more than it says.
     *
     * The killed-process note deliberately does not claim the app crashed: from the diary
     * alone an out-of-memory kill and a crash look identical, and only the crash reporter
     * can tell them apart. Saying "ended without warning" is what the evidence supports.
     */
    fun note(entries: List<DiaryEntry>, index: Int): String? {
        val entry = entries.getOrNull(index) ?: return null
        return when (entry.event) {
            PlaybackDiary.PROCESS_STARTED -> {
                val previous = entries.getOrNull(index - 1) ?: return null
                if (previous.event in IN_PROGRESS) {
                    "Playback was still going when lugu last stopped, so the app was ended " +
                        "without warning — either the system reclaimed it or it crashed."
                } else {
                    null
                }
            }
            PlaybackEvent.SUPPRESSED ->
                "Something else took the audio, or the output was not one lugu may play to."
            PlaybackEvent.UNEXPECTED_STOP ->
                "The player stopped on its own, with nothing asking it to."
            else -> null
        }
    }

    /** Midnight before [atMs] in the phone's own time zone, which is the day the reader means. */
    fun startOfDay(atMs: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = atMs
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /** "Today" and "Yesterday" are what someone recalls; anything older wants its date. */
    fun dayLabel(dayStartMs: Long, nowMs: Long): String {
        val today = startOfDay(nowMs)
        val yesterday = startOfDay(today - 1)
        return when (dayStartMs) {
            today -> "Today"
            yesterday -> "Yesterday"
            else -> DAY_FORMAT.format(Date(dayStartMs))
        }
    }

    /**
     * The events that mean playback was in progress when the record stops. A restart
     * following any of these is a stop nobody asked for.
     */
    private val IN_PROGRESS = setOf(
        PlaybackEvent.PLAY_REQUESTED,
        PlaybackEvent.PLAYING,
        PlaybackEvent.BUFFERING,
    )

    private val DAY_FORMAT = SimpleDateFormat("d MMMM yyyy", Locale.UK)
}
