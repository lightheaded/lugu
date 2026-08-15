package io.github.lightheaded.lugu.feature.player

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.Chapter
import org.junit.Test

class ChapterRowsTest {

    private val chapters = listOf(
        Chapter(id = 0, startSec = 0.0, endSec = 600.0, title = "One"),
        Chapter(id = 1, startSec = 600.0, endSec = 1200.0, title = "Two"),
        Chapter(id = 2, startSec = 1200.0, endSec = 1500.0, title = "Three"),
    )

    @Test
    fun `marks the chapter containing the position`() {
        val rows = chapterRows(chapters, positionSec = 700.0)

        assertThat(rows.map { it.isCurrent }).containsExactly(false, true, false).inOrder()
        assertThat(rows.map { it.number }).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun `a position exactly on a boundary belongs to the chapter it starts`() {
        val rows = chapterRows(chapters, positionSec = 600.0)

        assertThat(rows.single { it.isCurrent }.chapter.title).isEqualTo("Two")
    }

    @Test
    fun `progress is offered only for the chapter in progress`() {
        val rows = chapterRows(chapters, positionSec = 900.0)

        assertThat(rows[0].progress).isNull()
        assertThat(rows[1].progress).isWithin(0.001f).of(0.5f)
        assertThat(rows[2].progress).isNull()
    }

    @Test
    fun `a position before the first chapter marks nothing`() {
        val late = listOf(Chapter(id = 0, startSec = 30.0, endSec = 90.0, title = "Late"))

        assertThat(chapterRows(late, positionSec = 0.0).none { it.isCurrent }).isTrue()
    }

    @Test
    fun `a zero length chapter reads as finished rather than as not started`() {
        val degenerate = listOf(Chapter(id = 0, startSec = 0.0, endSec = 0.0, title = "Empty"))

        assertThat(chapterRows(degenerate, positionSec = 0.0).single().progress).isEqualTo(1f)
    }

    @Test
    fun `no chapters means no rows`() {
        assertThat(chapterRows(emptyList(), positionSec = 42.0)).isEmpty()
    }
}
