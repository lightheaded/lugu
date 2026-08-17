package io.github.lightheaded.lugu.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * What the app is doing, or what it has just done, in the one line that says so.
 *
 * Three kinds because they behave differently on screen and not because they read
 * differently: work comes and goes on its own and must not be announced for the fraction of
 * a second it usually takes, a confirmation is a reply to something just pressed and has to
 * arrive at once, and a problem is the only one of the three that may not disappear by
 * itself.
 */
sealed interface Status {
    val text: String

    /**
     * Something is happening now.
     *
     * [fraction] is `null` until the size of the job is known, which for a library sync is
     * only after the server has said how many items there are.
     */
    data class Working(override val text: String, val fraction: Float? = null) : Status

    /** Something finished, and left nothing visible behind to prove it. */
    data class Done(override val text: String) : Status

    /** Something failed. Stays until it is dismissed or replaced. */
    data class Problem(override val text: String) : Status
}

/**
 * The line under the top bar where lugu says what it is doing.
 *
 * **It must be laid out as an overlay** — inside a `Box`, aligned to the top of the content,
 * never as a child of the `Column` it appears above. That is the whole point of it. What it
 * replaces was a spinner in the top bar's actions, which is a right-aligned row: a spinner
 * appearing at the end of it pushed the queue, downloads and settings buttons sideways and
 * then let them slide back, so the app twitched every time a sync started and finished —
 * including the one that runs by itself the moment the app opens, which is why the movement
 * happened when nobody had touched anything.
 *
 * Two further faults it is meant to fix. The spinner never said *what* was being fetched,
 * and a sync that finished quickly showed it for long enough to be noticed and not long
 * enough to be read — a flicker with no meaning, which is worse than no indicator at all.
 * So work has to last [APPEAR_AFTER_MS] before anything is drawn, and once drawn the line
 * stays [LINGER_MS] after the work ends, because a bar that vanishes the instant it fills
 * reads as a glitch rather than as a finish.
 *
 * The text is the part Tom asked for and the part that has to earn its place: it sits over
 * the top edge of the content, so it is spent only while something is genuinely happening.
 */
@Composable
fun StatusStrip(
    status: Status?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Only work is gated on time. A confirmation and a failure are both replies to
    // something a person just did, and a reply that arrives half a second late reads as
    // having been caused by whatever they did next.
    val working = status as? Status.Working
    val showWork = settledVisibility(working != null)

    // The last thing said, kept so that the linger and the fade have something to draw
    // after the state behind them has already gone. It trails the current value by one
    // composition, which is exactly the relationship wanted: it is only ever read once
    // `shown` is null.
    var last by remember { mutableStateOf<Status?>(null) }

    val shown: Status? = when {
        status is Status.Problem || status is Status.Done -> status
        showWork -> working ?: last as? Status.Working
        else -> null
    }
    LaunchedEffect(shown) { if (shown != null) last = shown }

    // A confirmation takes itself away. Nothing else on screen changed to prove the action
    // happened — that is why there is a line at all — but it is a statement about a moment,
    // and a moment does not stay true.
    LaunchedEffect(status) {
        if (status is Status.Done) {
            delay(DONE_MS)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = shown != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        // Captured so the fade-out has something to draw after the state has already gone.
        val note = shown ?: last ?: return@AnimatedVisibility
        val problem = note is Status.Problem
        Column(Modifier.fillMaxWidth()) {
            when (note) {
                is Status.Working -> if (note.fraction != null) {
                    LinearProgressIndicator(
                        progress = { note.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(LINE_HEIGHT),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(LINE_HEIGHT),
                    )
                }
                // A finished or failed thing has no progress left to show, and a full bar
                // above a failure would be a contradiction.
                else -> Unit
            }
            Surface(
                color = if (problem) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                // Lifted a little, because it is drawn over the top of a list rather than
                // above it: without a shadow the band reads as a row that has cut the first
                // line of content in half, rather than as something laid over it.
                shadowElevation = 3.dp,
                // Only a problem can be dismissed by hand; the other two manage it
                // themselves, and a tap target that usually does nothing is worse than none.
                modifier = if (problem) Modifier.clickable(onClick = onDismiss) else Modifier,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        // Announced without stealing focus: a screen reader in the middle of
                        // a book's title should finish it before hearing that a sync began.
                        .semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    Text(
                        note.text,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (problem) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // The band being tappable is invisible without this. A cross is the
                    // whole affordance; the tap target is the band, which is far larger
                    // than the mark that advertises it.
                    if (problem) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * True once work has gone on long enough to be worth mentioning, and for a moment after.
 *
 * Split out from the drawing because it is the whole of the behaviour worth testing: what
 * this returns for a job that takes 80ms is the difference between a calm screen and the
 * flicker being fixed.
 */
@Composable
private fun settledVisibility(active: Boolean): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            delay(APPEAR_AFTER_MS)
            visible = true
        } else if (visible) {
            delay(LINGER_MS)
            visible = false
        }
    }
    return visible
}

/**
 * Long enough that an ordinary sync of an already-mirrored library says nothing at all.
 *
 * Measured against the thing being hidden rather than picked from a guideline: the sync
 * that runs when the app opens usually finds nothing changed and is over well inside this,
 * and that is exactly the one that had been twitching the top bar on every launch.
 */
private const val APPEAR_AFTER_MS = 400L

/** So a bar that has just filled is seen to finish rather than seen to disappear. */
private const val LINGER_MS = 600L

/** A short sentence, read once, by someone who already knows what they pressed. */
private const val DONE_MS = 4_000L

/** Thin on purpose: it is a fact about the app, not a thing to look at. */
private val LINE_HEIGHT = 3.dp
