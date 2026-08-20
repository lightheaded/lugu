package io.github.lightheaded.lugu.feature.library

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
 */
@Composable
internal fun ShowNotesText(
    html: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    val notes = remember(html) { parseShowNotes(html) }
    ShowNotesText(notes = notes, modifier = modifier, style = style)
}

@Composable
internal fun ShowNotesText(
    notes: ShowNotes,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
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

    Text(annotated, style = style, modifier = modifier)
}
