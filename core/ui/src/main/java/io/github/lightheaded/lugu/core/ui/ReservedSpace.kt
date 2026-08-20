package io.github.lightheaded.lugu.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/**
 * The other half of the rule recorded on [StatusStrip]: a message that belongs beside the
 * input it is about, in space that is reserved whether or not there is anything to say.
 *
 * The block is exactly [lines] lines tall in both states, so a rejected password appears
 * where a reader is already looking and moves nothing — not the Sign in button under it,
 * and not the field the password was typed into. That is why the height is fixed at both
 * ends rather than only at the bottom: a message two lines long and a message one line long
 * must occupy the same space as no message at all.
 *
 * The cost is the space itself, and it is paid on purpose. The alternative that was tried
 * is the one being removed: a line added to a column, which pushes everything below it down
 * at the exact moment somebody is reading it.
 *
 * Announced politely when it appears, and invisible to a screen reader when it does not, so
 * that reserved space is never read out as an empty line.
 */
@Composable
fun ReservedMessage(
    message: String?,
    modifier: Modifier = Modifier,
    lines: Int = 2,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = MaterialTheme.colorScheme.error,
) {
    Text(
        text = message.orEmpty(),
        style = style,
        color = color,
        minLines = lines,
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.reservedSpace(message != null),
    )
}

/**
 * Draw this or do not, but keep its space either way.
 *
 * For text the screen supplies itself, whose length is therefore known: the sign-in
 * screen's plain-HTTP warning is the same words every time it is true, so composing it
 * always and hiding it with alpha gives a block of identical height in both states without
 * anybody having to count lines.
 *
 * Both halves of the accessibility rule are here rather than at the call sites, because a
 * caller that remembers one and forgets the other produces the two faults this exists to
 * prevent: a warning that appears and is never announced, and a hidden line that a screen
 * reader reads out to somebody who cannot see that it is not there.
 */
fun Modifier.reservedSpace(visible: Boolean): Modifier = if (visible) {
    semantics { liveRegion = LiveRegionMode.Polite }
} else {
    alpha(0f).clearAndSetSemantics {}
}
