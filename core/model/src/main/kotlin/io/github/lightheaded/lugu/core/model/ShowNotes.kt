package io.github.lightheaded.lugu.core.model

/** What a run of show notes carries beyond plain type. */
enum class NoteStyle { BOLD, ITALIC, LINK }

/**
 * One run of [ShowNotes.text], and what it is.
 *
 * Held as offsets into the flattened text rather than as a tree of nodes. Show notes are
 * a paragraph or two with some links in them, never a document with structure worth
 * walking, and offsets are exactly what a Compose `AnnotatedString` wants — so a tree
 * would be built only to be flattened again by its one reader.
 */
data class NoteSpan(
    val start: Int,
    val end: Int,
    val style: NoteStyle,
    /** Where a [NoteStyle.LINK] leads. Null for the other styles. */
    val href: String? = null,
)

/** Show notes after the markup is gone: the words, and where the emphasis and links are. */
data class ShowNotes(val text: String, val spans: List<NoteSpan> = emptyList()) {
    val isEmpty: Boolean get() = text.isBlank()

    companion object {
        val EMPTY = ShowNotes("")
    }
}

/**
 * Turns a feed's description into text a screen can draw.
 *
 * ### Why this is written here rather than taken from a library
 *
 * A podcast description is HTML, and until now lugu drew it with a plain `Text` — so a
 * feed that writes in `<p>` and `<a>` showed its tags. Three things decide the fix, and
 * they point the same way.
 *
 * The links are the point. Show notes exist to say "the thing we talked about is here",
 * so a renderer that keeps the words and drops the addresses answers the wrong half of
 * the question. That rules out any strip-the-tags one-liner.
 *
 * `HtmlCompat.fromHtml` is the obvious platform answer and is the wrong one twice over.
 * It returns a `Spanned`, which is an Android type, so the parsing could not live in
 * `:core:model` with the rest of lugu's pure logic and could not be tested without
 * Robolectric. Worse, what it accepts is a property of the platform version rather than
 * of this repository: the same feed renders differently on two phones, and the difference
 * arrives with an OS upgrade nobody connects to it. A baseline that moves on its own is
 * not a baseline.
 *
 * A real HTML parser (jsoup and the rest) is a dependency, and the input here is a
 * paragraph of text. This file is about two hundred lines, has no transitive graph, and
 * fails in ways a test can state. That is the smaller correct thing.
 *
 * ### What it does
 *
 * Block tags become line breaks, `<li>` becomes a bullet, `<b>` and `<i>` and their
 * spelled-out twins become spans, and `<a href>` becomes a link. Every other tag is
 * dropped and its text is kept, because a tag this does not know is far more likely to be
 * decoration than to be content — and dropping the *text* would lose the show notes to
 * keep the markup.
 *
 * ### The ugly cases, and what each one does
 *
 * - **A tag that never closes.** Every open span is closed at the end of the text, so
 *   `<b>` at the top does not turn a lost bold into no text at all.
 *   Tags closed out of order are tolerated the same way.
 * - **A `<` that is not a tag.** "a < b" is prose, so a `<` not followed by a letter or a
 *   slash stays as it is. A tag-looking run with no `>` at all is dropped: it is a
 *   truncated tag, and printing it would be printing markup.
 * - **Entities.** The numeric forms and the named ones a feed actually uses are decoded,
 *   so `&amp;` is an ampersand and `&#8217;` is an apostrophe. An entity this does not
 *   know is left exactly as written, which is the only reading that cannot invent text.
 * - **No markup at all.** A description with no tags in it is not HTML and is not treated
 *   as such: it keeps its own line breaks and its own spacing, and only its entities are
 *   decoded. Collapsing that text the way HTML collapses whitespace would destroy the
 *   layout of every plain-text feed to serve none.
 * - **Nothing there.** Null, blank, or markup with no words in it all give
 *   [ShowNotes.EMPTY], so a caller has one emptiness to test rather than three.
 *
 * ### Links that are not offered
 *
 * Only `http`, `https` and `mailto` survive. A `javascript:` or `intent:` address in a
 * description is at best broken and at worst an attempt at something, and a relative
 * address has no base to be resolved against here. Those keep their words and lose their
 * link, which is the failure that costs a reader least.
 */
fun parseShowNotes(source: String?): ShowNotes {
    val raw = source?.takeIf { it.isNotBlank() } ?: return ShowNotes.EMPTY
    if (!MARKUP.containsMatchIn(raw)) {
        return decodeEntities(raw).trim().takeIf { it.isNotEmpty() }?.let { ShowNotes(it) }
            ?: ShowNotes.EMPTY
    }
    return ShowNotesParser(raw).parse()
}

/** A `<` that starts a tag: a letter, or a slash for a closing one. Anything else is prose. */
private val MARKUP = Regex("</?[A-Za-z]")

/** Runs of ordinary space between words, which HTML draws as one space however long. */
private val SPACE_RUN = Regex("[ \\t\\r\\n\\u000C]+")

/** `href="..."`, `href='...'` or bare. Taken from the tag body, which is already isolated. */
private val HREF = Regex("""href\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

/**
 * Tags that end a block of text, and so are worth a blank line between them.
 *
 * `<ul>` and `<ol>` are deliberately absent. A list belongs to the line that introduces
 * it, and a blank line between "the links are" and the first bullet reads as two
 * unrelated things. Each `<li>` breaks its own line, which is all a list needs.
 */
private val BLOCK_TAGS = setOf(
    "p", "div", "section", "article", "header", "footer", "blockquote", "pre",
    "table", "tr", "h1", "h2", "h3", "h4", "h5", "h6",
)

/** Tags whose contents are code rather than words, and are dropped whole. */
private val SILENT_TAGS = setOf("script", "style", "head", "iframe", "noscript")

/** How much white space is owed before the next word is written. */
private const val BREAK_NONE = 0
private const val BREAK_LINE = 1
private const val BREAK_PARAGRAPH = 2

/**
 * One pass over one description.
 *
 * A class rather than a function with six locals, because the open-span stack and the
 * owed break have to be read and written from every branch of the loop.
 */
private class ShowNotesParser(private val source: String) {

    private val out = StringBuilder()
    private val spans = mutableListOf<NoteSpan>()
    private val open = ArrayDeque<OpenSpan>()
    private var owedBreak = BREAK_NONE

    private data class OpenSpan(val style: NoteStyle, val start: Int, val href: String?)

    fun parse(): ShowNotes {
        var index = 0
        while (index < source.length) {
            val next = source.indexOf('<', index)
            if (next < 0) {
                appendText(source.substring(index))
                break
            }
            appendText(source.substring(index, next))
            index = readTag(next)
        }
        // Whatever is still open is closed where the text stops. A feed that opens a tag
        // and forgets it is common; losing the rest of the notes over it is not acceptable.
        while (open.isNotEmpty()) closeFrame(open.removeLast(), out.length)

        val text = out.toString().trimEnd()
        if (text.isBlank()) return ShowNotes.EMPTY
        val kept = spans
            .filter { it.start < it.end && it.end <= text.length }
            .filterNot { it.style == NoteStyle.LINK && it.href == null }
            .sortedBy { it.start }
        return ShowNotes(text, kept)
    }

    /** Reads the tag that starts at [at], acts on it, and returns where the text resumes. */
    private fun readTag(at: Int): Int {
        val after = source.getOrNull(at + 1)
        // "a < b" is prose, not a tag, and a reader would rather see it than lose it.
        if (after == null || !(after.isLetter() || after == '/' || after == '!')) {
            appendText("<")
            return at + 1
        }
        val end = source.indexOf('>', at)
        // A tag with no end is a truncated document. Its text is gone either way; printing
        // the fragment would only print markup.
        if (end < 0) return source.length
        val body = source.substring(at + 1, end)
        if (body.startsWith("!")) return end + 1

        val closing = body.startsWith("/")
        val name = body.removePrefix("/").takeWhile { !it.isWhitespace() && it != '/' }.lowercase()

        if (name in SILENT_TAGS) return if (closing) end + 1 else skipSilent(name, end + 1)

        when {
            name == "br" -> owe(BREAK_LINE)
            name == "li" -> {
                owe(BREAK_LINE)
                if (!closing) {
                    flushBreak()
                    out.append("• ")
                }
            }
            name in BLOCK_TAGS -> owe(BREAK_PARAGRAPH)
            name == "b" || name == "strong" -> frame(NoteStyle.BOLD, closing, null)
            name == "i" || name == "em" -> frame(NoteStyle.ITALIC, closing, null)
            name == "a" -> frame(NoteStyle.LINK, closing, if (closing) null else hrefOf(body))
        }
        return end + 1
    }

    /** Drops a script or a style whole, contents included. */
    private fun skipSilent(name: String, from: Int): Int {
        val close = source.indexOf("</$name", from, ignoreCase = true)
        if (close < 0) return source.length
        val end = source.indexOf('>', close)
        return if (end < 0) source.length else end + 1
    }

    private fun frame(style: NoteStyle, closing: Boolean, href: String?) {
        if (!closing) {
            // The break a block tag owes is paid first, so a span opened straight after a
            // paragraph starts at the word rather than at the line break before it — an
            // underlined link that begins on the line above is the visible version of this.
            flushBreak()
            open.addLast(OpenSpan(style, out.length, href))
            return
        }
        // Closed out of order, everything opened after the match closes with it. Feeds
        // write "<b><i>text</b></i>" often enough that refusing to read it is not an option.
        val match = open.indexOfLast { it.style == style }
        if (match < 0) return
        while (open.size > match) closeFrame(open.removeLast(), out.length)
    }

    private fun closeFrame(frame: OpenSpan, end: Int) {
        spans += NoteSpan(frame.start, end, frame.style, frame.href)
    }

    private fun hrefOf(body: String): String? {
        val match = HREF.find(body) ?: return null
        val value = (match.groupValues[2].takeIf { it.isNotEmpty() }
            ?: match.groupValues[3].takeIf { it.isNotEmpty() }
            ?: match.groupValues[4])
        val url = decodeEntities(value).trim()
        val scheme = url.substringBefore(':', "").lowercase()
        return url.takeIf { scheme == "http" || scheme == "https" || scheme == "mailto" }
    }

    private fun owe(amount: Int) {
        owedBreak = maxOf(owedBreak, amount)
    }

    private fun appendText(chunk: String) {
        if (chunk.isEmpty()) return
        val text = SPACE_RUN.replace(decodeEntities(chunk), " ")
        if (text.isBlank()) {
            // White space between two tags is a word separator and nothing more. It is
            // never allowed to start the text or to compete with a break already owed.
            if (out.isNotEmpty() && owedBreak == BREAK_NONE && !out.last().isWhitespace()) {
                out.append(' ')
            }
            return
        }
        flushBreak()
        // A line that has just been broken, or a bullet just written, already ends in
        // space. Text arriving with its own leading space would double it.
        val spaced = out.isEmpty() || out.last().isWhitespace()
        out.append(if (spaced) text.trimStart() else text)
    }

    private fun flushBreak() {
        if (owedBreak == BREAK_NONE) return
        val amount = owedBreak
        owedBreak = BREAK_NONE
        // Nothing to separate yet: a document that opens with <p> must not open with a gap.
        if (out.isEmpty()) return
        while (out.isNotEmpty() && out.last() == ' ') out.deleteCharAt(out.length - 1)
        val already = out.takeLastWhile { it == '\n' }.length
        repeat(amount - already) { out.append('\n') }
    }
}

/**
 * The entities a podcast feed actually writes.
 *
 * A full table is a few thousand rows and buys nothing: everything outside this list is
 * either rare enough never to have been seen or writable as the character itself, which
 * feeds mostly do. Anything unlisted is left as written — a reader seeing "&dagger;" has
 * lost nothing, where a reader seeing the wrong character has been told something untrue.
 */
private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to " ", "shy" to "",
    "hellip" to "…", "mdash" to "—", "ndash" to "–", "minus" to "−",
    "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚",
    "ldquo" to "“", "rdquo" to "”", "bdquo" to "„",
    "laquo" to "«", "raquo" to "»", "bull" to "•", "middot" to "·",
    "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°",
    "eacute" to "é", "egrave" to "è", "agrave" to "à", "ccedil" to "ç",
    "uuml" to "ü", "ouml" to "ö", "auml" to "ä", "szlig" to "ß", "ntilde" to "ñ",
    "pound" to "£", "euro" to "€", "yen" to "¥", "cent" to "¢",
)

/** `&amp;`, `&#8217;` and `&#x2019;`, and nothing invented for anything else. */
private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]{1,31});")

internal fun decodeEntities(text: String): String {
    if ('&' !in text) return text
    return ENTITY.replace(text) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") ->
                codePoint(body.drop(2).toIntOrNull(16)) ?: match.value

            body.startsWith("#") -> codePoint(body.drop(1).toIntOrNull()) ?: match.value
            else -> NAMED_ENTITIES[body.lowercase()] ?: match.value
        }
    }
}

/** A number outside Unicode, or naming a control character, is a broken feed rather than text. */
private fun codePoint(value: Int?): String? {
    if (value == null || value !in 0x20..0x10FFFF) return null
    return runCatching { String(Character.toChars(value)) }.getOrNull()
}
