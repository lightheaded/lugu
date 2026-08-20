package io.github.lightheaded.lugu.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What a feed's description is allowed to do to a screen.
 *
 * The rule these all serve is one line: the page must never show a tag, and must never
 * lose a link. Everything below is a way a real feed has of breaking one of the two.
 */
class ShowNotesTest {

    @Test
    fun `paragraphs become blank lines and the tags go`() {
        val notes = parseShowNotes("<p>First thing.</p><p>Second thing.</p>")

        assertThat(notes.text).isEqualTo("First thing.\n\nSecond thing.")
        assertThat(notes.text).doesNotContain("<")
    }

    @Test
    fun `a break is one line and a list is bullets`() {
        val notes = parseShowNotes("One<br>Two<ul><li>Three</li><li>Four</li></ul>")

        assertThat(notes.text).isEqualTo("One\nTwo\n• Three\n• Four")
    }

    @Test
    fun `a link keeps both its words and its address`() {
        val notes = parseShowNotes("""Read <a href="https://example.org/notes">the notes</a> first.""")

        assertThat(notes.text).isEqualTo("Read the notes first.")
        val link = notes.spans.single { it.style == NoteStyle.LINK }
        assertThat(link.href).isEqualTo("https://example.org/notes")
        assertThat(notes.text.substring(link.start, link.end)).isEqualTo("the notes")
    }

    @Test
    fun `a single-quoted or bare href is read the same way`() {
        assertThat(parseShowNotes("<a href='https://example.org/a'>a</a>").spans.single().href)
            .isEqualTo("https://example.org/a")
        assertThat(parseShowNotes("<a href=https://example.org/b>b</a>").spans.single().href)
            .isEqualTo("https://example.org/b")
    }

    @Test
    fun `emphasis survives as a span rather than as tags`() {
        val notes = parseShowNotes("A <strong>loud</strong> and <em>quiet</em> word.")

        assertThat(notes.text).isEqualTo("A loud and quiet word.")
        val bold = notes.spans.single { it.style == NoteStyle.BOLD }
        assertThat(notes.text.substring(bold.start, bold.end)).isEqualTo("loud")
        val italic = notes.spans.single { it.style == NoteStyle.ITALIC }
        assertThat(notes.text.substring(italic.start, italic.end)).isEqualTo("quiet")
    }

    @Test
    fun `an address that is not a web address keeps its words and loses its link`() {
        val notes = parseShowNotes("""<a href="javascript:alert(1)">tap here</a>""")

        assertThat(notes.text).isEqualTo("tap here")
        assertThat(notes.spans).isEmpty()
    }

    @Test
    fun `a relative address is not guessed at`() {
        val notes = parseShowNotes("""<a href="/episodes/14">episode 14</a>""")

        assertThat(notes.text).isEqualTo("episode 14")
        assertThat(notes.spans).isEmpty()
    }

    @Test
    fun `a mail address is a link`() {
        assertThat(parseShowNotes("""<a href="mailto:post@example.org">write in</a>""").spans.single().href)
            .isEqualTo("mailto:post@example.org")
    }

    // ---------------------------------------------------------------------------------
    // The ugly real-world cases.
    // ---------------------------------------------------------------------------------

    @Test
    fun `a tag that is never closed does not swallow the notes`() {
        val notes = parseShowNotes("<p>The lamp was <b>already lit")

        assertThat(notes.text).isEqualTo("The lamp was already lit")
        val bold = notes.spans.single()
        assertThat(notes.text.substring(bold.start, bold.end)).isEqualTo("already lit")
    }

    @Test
    fun `a link that is never closed still leads somewhere`() {
        val notes = parseShowNotes("""Notes: <a href="https://example.org/x">the page""")

        assertThat(notes.text).isEqualTo("Notes: the page")
        assertThat(notes.spans.single().href).isEqualTo("https://example.org/x")
    }

    @Test
    fun `tags closed out of order are read rather than refused`() {
        val notes = parseShowNotes("<b><i>both</b></i> after")

        assertThat(notes.text).isEqualTo("both after")
        assertThat(notes.spans.map { it.style })
            .containsExactly(NoteStyle.BOLD, NoteStyle.ITALIC)
    }

    @Test
    fun `a tag with no closing bracket is dropped rather than printed`() {
        val notes = parseShowNotes("<p>A keeper who has not spoken<b class=")

        assertThat(notes.text).isEqualTo("A keeper who has not spoken")
    }

    @Test
    fun `a less-than that is arithmetic stays as it was written`() {
        val notes = parseShowNotes("<p>Everything under 5 < 6 minutes.</p>")

        assertThat(notes.text).isEqualTo("Everything under 5 < 6 minutes.")
    }

    @Test
    fun `named entities are decoded`() {
        assertThat(parseShowNotes("<p>Salt &amp; Shortwave</p>").text).isEqualTo("Salt & Shortwave")
        assertThat(parseShowNotes("<p>&lt;p&gt; is a tag</p>").text).isEqualTo("<p> is a tag")
        assertThat(parseShowNotes("<p>Wait&hellip;</p>").text).isEqualTo("Wait…")
    }

    @Test
    fun `numeric entities are decoded in both bases`() {
        assertThat(parseShowNotes("<p>the keeper&#8217;s lamp</p>").text)
            .isEqualTo("the keeper’s lamp")
        assertThat(parseShowNotes("<p>the keeper&#x2019;s lamp</p>").text)
            .isEqualTo("the keeper’s lamp")
    }

    @Test
    fun `an entity nobody knows is left exactly as written`() {
        assertThat(parseShowNotes("<p>Corven&dagger; and Vale</p>").text)
            .isEqualTo("Corven&dagger; and Vale")
    }

    @Test
    fun `an escaped tag is text and never becomes a tag`() {
        val notes = parseShowNotes("<p>Write &lt;b&gt;bold&lt;/b&gt; to shout.</p>")

        assertThat(notes.text).isEqualTo("Write <b>bold</b> to shout.")
        assertThat(notes.spans).isEmpty()
    }

    @Test
    fun `plain text with no markup keeps its own line breaks`() {
        val plain = "The lamp was already lit.\n\nNobody had been up the stairs in four months."

        assertThat(parseShowNotes(plain).text).isEqualTo(plain)
        assertThat(parseShowNotes(plain).spans).isEmpty()
    }

    @Test
    fun `plain text still has its entities decoded`() {
        assertThat(parseShowNotes("Salt &amp; Shortwave").text).isEqualTo("Salt & Shortwave")
    }

    @Test
    fun `nothing at all is one emptiness rather than three`() {
        assertThat(parseShowNotes(null)).isEqualTo(ShowNotes.EMPTY)
        assertThat(parseShowNotes("")).isEqualTo(ShowNotes.EMPTY)
        assertThat(parseShowNotes("   \n  ")).isEqualTo(ShowNotes.EMPTY)
        assertThat(parseShowNotes("<p></p><br><div></div>")).isEqualTo(ShowNotes.EMPTY)
        assertThat(parseShowNotes("<p>&nbsp;</p>").isEmpty).isTrue()
    }

    @Test
    fun `white space between tags never opens or doubles`() {
        val notes = parseShowNotes("  <p>  The lamp   was lit.  </p>\n  <p>Twice.</p>  ")

        assertThat(notes.text).isEqualTo("The lamp was lit.\n\nTwice.")
    }

    @Test
    fun `a script is dropped whole rather than read as words`() {
        val notes = parseShowNotes("<p>Notes</p><script>var x = 1;</script><p>More</p>")

        assertThat(notes.text).isEqualTo("Notes\n\nMore")
    }

    @Test
    fun `a tag nobody knows keeps its words`() {
        val notes = parseShowNotes("""<p>A <span class="x">quiet</span> hour</p>""")

        assertThat(notes.text).isEqualTo("A quiet hour")
    }

    @Test
    fun `a comment is not read as text`() {
        assertThat(parseShowNotes("<p>Before<!-- a note to nobody -->after</p>").text)
            .isEqualTo("Beforeafter")
    }

    @Test
    fun `a link after a paragraph starts at its own first word`() {
        val notes = parseShowNotes("""<p>One</p><p><a href="https://example.org/y">Two</a></p>""")

        val link = notes.spans.single()
        assertThat(notes.text.substring(link.start, link.end)).isEqualTo("Two")
    }
}
