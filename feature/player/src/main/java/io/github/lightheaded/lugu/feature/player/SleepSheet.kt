package io.github.lightheaded.lugu.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lightheaded.lugu.core.model.Chapter
import io.github.lightheaded.lugu.core.model.SleepMode
import io.github.lightheaded.lugu.core.model.SleepTimer
import io.github.lightheaded.lugu.core.model.SleepTimerState
import io.github.lightheaded.lugu.core.model.formatShortSeconds
import io.github.lightheaded.lugu.core.sync.SleepSettings
import kotlin.math.abs
import kotlin.math.ceil

/**
 * What the timer will do when it runs out, in one line.
 *
 * Both halves are configurable and both change what the listener experiences hours later,
 * in the dark, when they are least able to work out what happened. A timer that fades and
 * then rewinds half a minute looks like a bug to anyone who was not told.
 */
internal fun sleepExplanation(sleep: SleepSettings): String {
    val fade = if (sleep.fadeSeconds > 0) {
        "Fades out over ${formatShortSeconds(sleep.fadeSeconds)}"
    } else {
        "Stops without fading"
    }
    val rewind = if (sleep.rewindOnWakeSec > 0) {
        "rewinds ${formatShortSeconds(sleep.rewindOnWakeSec)} when you come back"
    } else {
        "starts again exactly where it stopped"
    }
    return "$fade, and $rewind"
}

/**
 * What a pause does to an armed timer, or null when there is nothing surprising to say.
 *
 * Kept apart from [sleepExplanation] because it answers a different question — that one is
 * about what happens when the timer runs out, this one about what happens before it does.
 * It is worth saying at all because a timer that outlives a pause is startling the first
 * time it happens: the book was stopped for twenty minutes and the timer ran anyway.
 */
internal fun sleepPauseExplanation(sleep: SleepSettings): String? =
    if (sleep.survivesPause) "Stays armed if you pause" else null

/**
 * Whether a chapter list is the book's own rather than one lugu invented for it.
 *
 * `Chapters.synthesise` fills in evenly spaced parts for an item the server has no
 * chapters for, and those arrive at the player looking exactly like real ones — there is
 * no flag on the list saying which it is. Counting them would turn "2 chapters" into
 * "twenty minutes" under a label that says something else, which is a duration wearing the
 * wrong name.
 *
 * So the list is judged by the synthesiser's own fingerprint: every part but the last is
 * exactly the same length, and every title is "Part n" in order. Either test alone would
 * catch a real book — a novel of even chapters, or one whose parts are genuinely named
 * Part 1 and Part 2 — so both must hold before a list is dismissed. A list of one is no
 * use here either way, since counting chapters needs more than one to count.
 */
internal fun hasRealChapters(chapters: List<Chapter>): Boolean =
    chapters.size > 1 && !looksSynthesised(chapters)

private fun looksSynthesised(chapters: List<Chapter>): Boolean {
    val named = chapters.withIndex().all { (index, chapter) -> chapter.title == "Part ${index + 1}" }
    if (!named) return false
    // The last one is short by whatever the book does not divide into, so it is excluded.
    val lengths = chapters.dropLast(1).map { it.endSec - it.startSec }
    val first = lengths.firstOrNull() ?: return false
    return lengths.all { abs(it - first) < 1.0 }
}

/**
 * Arming, extending and cancelling the sleep timer.
 *
 * Everything the timer will do is stated here rather than left in Settings, including the
 * shake gesture and what a pause does to it: a feature nobody has been told about is a
 * feature that does not exist, and this sheet is the only moment when a listener is
 * thinking about sleep.
 *
 * Three ways of saying when to stop are offered, and they are three different questions
 * rather than one question in three units. A duration is a decision about the evening; end
 * of chapter is a decision about the next few minutes; a chapter count is a decision about
 * the book, and it is the one people actually make when they say "two more chapters".
 *
 * [chapters] defaults to the item the player is holding, because the count offer must not
 * appear for a list lugu synthesised — see [hasRealChapters] — and that can only be judged
 * from the list itself. A caller may pass its own, which is what a test does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepTimerSheet(
    hasChapters: Boolean,
    presets: List<Int>,
    timer: SleepTimerState,
    settings: SleepSettings,
    speed: Float,
    chapters: List<Chapter> = hiltViewModel<PlayerViewModel>()
        .nowPlaying.collectAsStateWithLifecycle().value?.chapters.orEmpty(),
    chapterPresets: List<Int> = SleepTimer.PRESET_CHAPTERS,
    onPick: (SleepMode?) -> Unit,
    onExtend: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Sleep timer", style = MaterialTheme.typography.titleMedium)
            Text(
                sleepExplanation(settings),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sleepPauseExplanation(settings)?.let { pause ->
                Text(
                    pause,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (settings.shakeToExtend) {
                Text(
                    "Shake the phone to add ${settings.extendMinutes} minutes without " +
                        "finding the screen",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (timer.isArmed) {
                Spacer(Modifier.height(12.dp))
                Text(
                    remainingLabel(timer, speed),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The extension is whatever the listener set, so the button and the
                    // shake gesture buy the same amount of time and there is one number
                    // to remember rather than two.
                    AssistChip(
                        onClick = { onExtend(settings.extendMinutes) },
                        label = { Text("Add ${settings.extendMinutes} min") },
                    )
                    TextButton(onClick = { onPick(null) }) { Text("Turn off") }
                }
            }

            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { minutes ->
                    AssistChip(
                        onClick = { onPick(SleepMode.Duration(minutes)) },
                        label = { Text("$minutes min") },
                    )
                }
            }
            if (hasChapters) {
                Spacer(Modifier.height(12.dp))
                AssistChip(
                    onClick = { onPick(SleepMode.EndOfChapter) },
                    label = { Text("End of chapter") },
                )
                Text(
                    "Follows you if you skip a chapter",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Counts start at two. One chapter stops in exactly the same place as the End
            // of chapter chip above, and the same stop offered twice under two names only
            // makes a listener wonder what the difference is. A count longer than the book
            // is left out for the same reason: it would be the end of the book by another
            // name.
            val counts = chapterPresets.filter { it in 2..chapters.size }
            if (hasRealChapters(chapters) && counts.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    counts.forEach { count ->
                        AssistChip(
                            onClick = { onPick(SleepMode.Chapters(count)) },
                            label = { Text("$count chapters") },
                        )
                    }
                }
                Text(
                    "Counted from where you are now, so skipping ahead leaves one fewer",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The timer counts in playback seconds so that pausing does not burn it down, but a
 * listener counts in minutes of their own evening — so the figure shown is divided back
 * out by the speed.
 */
private fun remainingLabel(timer: SleepTimerState, speed: Float): String {
    val remaining = timer.remainingSec ?: return "Timer armed"
    if (timer.isFading) return "Fading out now"
    val minutes = ceil(wallClockSecondsAt(remaining, speed) / 60.0).toInt()
    return if (minutes <= 1) "Less than a minute left" else "About $minutes minutes left"
}
