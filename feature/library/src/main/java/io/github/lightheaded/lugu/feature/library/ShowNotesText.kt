package io.github.lightheaded.lugu.feature.library

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.lightheaded.lugu.core.model.NoteStyle
import io.github.lightheaded.lugu.core.model.ShowNotes
import io.github.lightheaded.lugu.core.model.parseShowNotes

/**
 * A show's or an episode's description, drawn as words rather than as markup.
 *
 * The parsing is in `:core:model` — see `parseShowNotes` for why lugu reads the HTML
 * itself instead of taking a dependency or calling `HtmlCompat`. This half is only the
 * drawing: spans of the flattened text become an `AnnotatedString`, and a link becomes a
 * real `LinkAnnotation` that the text field handles on its own.
 *
 * Links are coloured and underlined together. Colour alone is not an affordance in a
 * paragraph of body text — a reader has nothing to compare it against — and the underline
 * is what says a word can be pressed.
 *
 * Opening one is wrapped, like the item page's link out to the web client: a phone with no
 * browser installed throws rather than declining, and a link in show notes is not worth
 * crashing a page over.
 *
 * [collapsedMaxLines] folds a long text to that many lines with a "More" / "Less" toggle.
 * The default shows everything, which is what the episode page wants.
 */
@Composable
internal fun ShowNotesText(
    html: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    collapsedMaxLines: Int = Int.MAX_VALUE,
) {
    val notes = remember(html) { parseShowNotes(html) }
    ShowNotesText(notes = notes, modifier = modifier, style = style, collapsedMaxLines = collapsedMaxLines)
}

@Composable
internal fun ShowNotesText(
    notes: ShowNotes,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    collapsedMaxLines: Int = Int.MAX_VALUE,
) {
    if (notes.isEmpty) return
    val uriHandler = LocalUriHandler.current
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        ),
    )

    val annotated = remember(notes, linkStyles, uriHandler) {
        buildAnnotatedString {
            append(notes.text)
            notes.spans.forEach { span ->
                when (span.style) {
                    NoteStyle.BOLD ->
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold), span.start, span.end)

                    NoteStyle.ITALIC ->
                        addStyle(SpanStyle(fontStyle = FontStyle.Italic), span.start, span.end)

                    NoteStyle.LINK -> {
                        val href = span.href ?: return@forEach
                        addLink(
                            LinkAnnotation.Url(href, linkStyles) { link ->
                                (link as? LinkAnnotation.Url)?.let {
                                    runCatching { uriHandler.openUri(it.url) }
                                }
                            },
                            span.start,
                            span.end,
                        )
                    }
                }
            }
        }
    }

    if (collapsedMaxLines == Int.MAX_VALUE) {
        Text(annotated, style = style, modifier = modifier)
        return
    }

    var expanded by rememberSaveable { mutableStateOf(false) }
    val measurer = rememberTextMeasurer()

    // Decided while measuring, before the first draw, so that the toggle does not appear a
    // frame after the text and push the content below it down. Measured at the collapsed
    // height whatever the state, so the "Less" stays once expanded.
    BoxWithConstraints(modifier) {
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val overflows = remember(annotated, style, width, collapsedMaxLines) {
            measurer.measure(
                annotated,
                style = style,
                overflow = TextOverflow.Ellipsis,
                maxLines = collapsedMaxLines,
                constraints = Constraints(maxWidth = width),
            ).hasVisualOverflow
        }
        Column {
            Text(
                annotated,
                style = style,
                maxLines = if (expanded) Int.MAX_VALUE else collapsedMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().animateContentSize(),
            )
            // Only for a text that really is cut: a toggle that reveals nothing is noise.
            if (overflows) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) {
                    Text(if (expanded) "Less" else "More")
                }
            }
        }
    }
}
