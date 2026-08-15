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
import io.github.lightheaded.lugu.core.model.SleepMode
import io.github.lightheaded.lugu.core.model.SleepTimerState
import io.github.lightheaded.lugu.core.sync.SleepSettings
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
 * Arming, extending and cancelling the sleep timer.
 *
 * Everything the timer will do is stated here rather than left in Settings, including the
 * shake gesture: a feature nobody has been told about is a feature that does not exist,
 * and this sheet is the only moment when a listener is thinking about sleep.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepTimerSheet(
    hasChapters: Boolean,
    presets: List<Int>,
    timer: SleepTimerState,
    settings: SleepSettings,
    speed: Float,
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
