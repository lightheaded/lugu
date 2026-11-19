package io.github.lightheaded.lugu.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.lightheaded.lugu.core.ui.ReservedMessage
import kotlinx.coroutines.delay

/**
 * How long the sign-out button stays armed. Long enough to reach with the same hand,
 * short enough that an armed button nobody meant to touch disarms before it is forgotten.
 */
private const val SIGN_OUT_ARM_MILLIS = 4_000L

/** What a sign-out costs, said under the button. The same on every screen. */
internal const val SIGN_OUT_CONSEQUENCE =
    "Deletes this account's downloads and library on this device. Your place stays on the server."

/**
 * Sign out, in two taps.
 *
 * Signing out drops the tokens and deletes the downloads, so it is the one action that
 * undo cannot answer — and it sits one stray tap away from a thumb scrolling a list. It
 * arms instead of asking: the first tap changes what the button says, the second one does
 * it, and the button disarms itself if nothing follows. What it costs is under it. A
 * dialog was not the answer, for the reason the rest of this app does not use them.
 *
 * While [busy], the button keeps its armed size and shows a spinner, and takes no tap. The
 * sentence under it is always there, so arming moves nothing below. [problem], when
 * set, takes that space, because a sign-out that failed outranks what it would have cost.
 */
@Composable
internal fun SignOutButton(
    onSignOut: () -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    problem: String? = null,
) {
    var armed by remember { mutableStateOf(false) }
    if (armed && !busy) {
        LaunchedEffect(Unit) {
            delay(SIGN_OUT_ARM_MILLIS)
            armed = false
        }
    }
    // Disarmed when the work ends, so a failed sign-out asks for two taps again.
    LaunchedEffect(busy) { if (!busy) armed = false }

    Column(modifier) {
        TextButton(
            onClick = {
                if (armed) {
                    onSignOut()
                } else {
                    armed = true
                }
            },
            enabled = enabled && !busy,
            modifier = Modifier.semantics {
                stateDescription = when {
                    busy -> "Signing out."
                    armed -> "Armed. Tap again to sign out."
                    else -> "Needs two taps."
                }
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                // The armed words stay laid out under the spinner, so the button does not
                // shrink while the sign-out runs.
                Text(
                    if (armed || busy) "Tap again to sign out" else "Sign out",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.alpha(if (busy) 0f else 1f),
                )
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        ReservedMessage(
            // Always shown: a sentence that is there before the first tap says more than
            // an empty block that fills on the second.
            message = problem ?: SIGN_OUT_CONSEQUENCE,
            color = if (problem != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}
