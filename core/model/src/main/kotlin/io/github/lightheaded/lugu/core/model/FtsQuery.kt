package io.github.lightheaded.lugu.core.model

/**
 * Turns what someone typed into something SQLite's FTS4 will accept.
 *
 * This is a sanitiser before it is a feature. FTS4 `MATCH` takes a query *language*, not
 * a string: an unbalanced quote, a bare `-`, or a trailing `*` in the wrong place throws
 * an SQLite error rather than returning nothing. Since the search box runs on every
 * keystroke, half-typed input is the normal case, not the edge case — so anything that
 * cannot be expressed safely returns null and the caller falls back to a plain scan.
 */
object FtsQuery {
    /**
     * Prefix-matches every term, so results narrow as the word is typed rather than
     * appearing only once it is finished. Terms are ANDed: "corven ligh" finds Lighthouse
     * Wakes, which is what someone half-remembering a book actually types.
     */
    fun toMatchExpression(raw: String): String? {
        val terms = raw
            .split(TERM_SEPARATORS)
            .mapNotNull { term -> term.filter { it.isLetterOrDigit() }.takeIf { it.length >= MIN_TERM_LENGTH } }
        if (terms.isEmpty()) return null
        return terms.joinToString(" ") { "$it*" }
    }

    private val TERM_SEPARATORS = Regex("""\s+""")

    /**
     * One character is not worth an index lookup — nearly every book matches, and the
     * substring fallback gives better answers for a single letter anyway.
     */
    private const val MIN_TERM_LENGTH = 2
}
