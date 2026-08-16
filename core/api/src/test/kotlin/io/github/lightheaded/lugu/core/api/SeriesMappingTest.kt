package io.github.lightheaded.lugu.core.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which series a payload says a book is in, and what each of the two shapes can express.
 *
 * The distinction is the whole point of the mapping. The expanded payload carries the
 * server's join rows — a name, an id and a sequence per membership, however many there
 * are — and the minified one carries a single string those were rendered into. Reading
 * the second as though it were the first is what filed a book in two series under a series
 * that does not exist.
 */
class SeriesMappingTest {

    private fun item(
        id: String = "li_1",
        seriesName: String? = null,
        series: List<SeriesRefDto> = emptyList(),
    ) = LibraryItemDto(
        id = id,
        libraryId = "lib_1",
        mediaType = "book",
        media = MediaDto(
            metadata = MetadataDto(
                title = "Lighthouse Wakes",
                authorName = "James T. R. Corven",
                seriesName = seriesName,
                series = series,
            ),
        ),
    )

    @Test
    fun `the structured array gives every membership, with its own sequence`() {
        val dto = item(
            seriesName = "The Breakwater #2, Riverton #1",
            series = listOf(
                SeriesRefDto(id = "se_1", name = "The Breakwater", sequence = "2"),
                SeriesRefDto(id = "se_2", name = "Riverton", sequence = "1"),
            ),
        )

        val refs = dto.seriesRefs()

        assertThat(refs.map { it.name }).containsExactly("The Breakwater", "Riverton").inOrder()
        assertThat(refs.map { it.sequence }).containsExactly(2.0, 1.0).inOrder()
        assertThat(refs.first().id).isEqualTo("se_1")
    }

    /**
     * The minified payload has only the joined string, and one membership is the most that
     * can honestly be recovered from it — the last resort, unchanged from what the app did
     * before the structured read existed.
     */
    @Test
    fun `the joined string yields one membership and no id`() {
        val refs = item(seriesName = "The Breakwater #2").seriesRefs()

        assertThat(refs).hasSize(1)
        assertThat(refs.single().name).isEqualTo("The Breakwater")
        assertThat(refs.single().sequence).isEqualTo(2.0)
        assertThat(refs.single().id).isNull()
    }

    @Test
    fun `a book in no series has no memberships invented for it`() {
        assertThat(item().seriesRefs()).isEmpty()
        assertThat(item(seriesName = "  ").seriesRefs()).isEmpty()
    }

    /**
     * What the library-series listing needs. That listing states the membership itself and
     * then sends *minified* members, so the sequence has to come back out of the joined
     * string — but anchored on a name the listing already supplied, which is exactly the
     * ambiguity that made the blind parse unusable.
     */
    @Test
    fun `a known series name recovers its own sequence from a minified member`() {
        val dto = item(seriesName = "The Breakwater #2, Riverton #1")

        assertThat(dto.seriesRefFor("se_1", "The Breakwater"))
            .isEqualTo(io.github.lightheaded.lugu.core.model.SeriesRef("se_1", "The Breakwater", 2.0))
        assertThat(dto.seriesRefFor("se_2", "Riverton").sequence).isEqualTo(1.0)
    }

    @Test
    fun `a series the member carries no number for gets none`() {
        val ref = item(seriesName = "The Tidelands").seriesRefFor("se_3", "The Tidelands")

        assertThat(ref.name).isEqualTo("The Tidelands")
        assertThat(ref.sequence).isNull()
    }

    /**
     * The sequence is a free-text column on the server, so a payload that puts prose in it
     * has to leave the rest of the item intact rather than failing to parse.
     */
    @Test
    fun `a sequence that is not a number leaves the membership without a position`() {
        val dto = item(series = listOf(SeriesRefDto(id = "se_1", name = "The Tidelands", sequence = "Book Two")))

        assertThat(dto.seriesRefs().single().name).isEqualTo("The Tidelands")
        assertThat(dto.seriesRefs().single().sequence).isNull()
    }

    /**
     * The minified list payload has no `series` key at all — verified against the server's
     * own `oldMetadataToJSONMinified`, which lists `seriesName` and not it. An empty array
     * therefore means "this payload was minified" at least as often as it means "in no
     * series", which is why the joined string is still read when it is empty.
     */
    @Test
    fun `a payload with no series key falls back rather than reporting no series`() {
        val decoded = AbsJson.decodeFromString(
            LibraryItemDto.serializer(),
            """
            {"id":"li_1","libraryId":"lib_1","mediaType":"book",
             "media":{"metadata":{"title":"Lighthouse Wakes","seriesName":"The Breakwater #2"}}}
            """.trimIndent(),
        )

        assertThat(decoded.media?.metadata?.series).isEmpty()
        assertThat(decoded.seriesRefs().single().name).isEqualTo("The Breakwater")
    }

    @Test
    fun `a structured payload decodes ids, names and sequences`() {
        val decoded = AbsJson.decodeFromString(
            LibraryItemDto.serializer(),
            """
            {"id":"li_1","libraryId":"lib_1","mediaType":"book",
             "media":{"metadata":{"title":"Lighthouse Falls",
               "seriesName":"The Breakwater #2, The Tidelands",
               "series":[{"id":"se_1","name":"The Breakwater","sequence":"2"},
                         {"id":"se_2","name":"The Tidelands","sequence":null}]}}}
            """.trimIndent(),
        )

        val refs = decoded.seriesRefs()
        assertThat(refs.map { it.name }).containsExactly("The Breakwater", "The Tidelands").inOrder()
        assertThat(refs.map { it.sequence }).containsExactly(2.0, null).inOrder()
    }
}
