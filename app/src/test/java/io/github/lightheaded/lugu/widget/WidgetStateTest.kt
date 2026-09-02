package io.github.lightheaded.lugu.widget

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.db.InProgressRow
import io.github.lightheaded.lugu.core.db.LibraryItemEntity
import org.junit.Test

/**
 * The arithmetic behind the widget, which is the only part of it a test can reach.
 *
 * Glance renders through `RemoteViews` and has no host outside a real launcher, so nothing
 * here photographs the widget. What it does check is the two numbers on it, and both have
 * a failure that a launcher would show and nobody would be able to explain:
 *
 * - A progress bar past its own width draws as a full bar on some launchers and as nothing
 *   on others. A duration the server has corrected downwards puts a stored position past
 *   the end, so this is reachable without any bug in lugu.
 * - "Time left" computed the obvious way goes negative in the same case, and a widget
 *   reading "-4 min left" is the kind of thing that gets reported as corruption.
 */
class WidgetStateTest {

    private fun row(
        title: String = "Lighthouse Wakes",
        author: String? = "James T. R. Corven",
        positionSec: Double = 600.0,
        durationSec: Double = 3_600.0,
        progressFraction: Double = 600.0 / 3_600.0,
    ) = InProgressRow(
        item = LibraryItemEntity(
            serverId = "s",
            userId = "u",
            id = "li_1",
            libraryId = "lib",
            mediaType = "book",
            title = title,
            subtitle = null,
            authorName = author,
            narratorName = null,
            seriesName = null,
            seriesTitle = null,
            seriesSequence = null,
            description = null,
            durationSec = durationSec,
            sizeBytes = 0,
            numEpisodes = 0,
            addedAtMs = 0,
            updatedAtMs = 0,
            coverPath = null,
            rawJson = "{}",
            syncedAtMs = 0,
        ),
        episodeId = null,
        episodeTitle = null,
        positionSec = positionSec,
        playedDurationSec = durationSec,
        progressFraction = progressFraction,
        lastUpdateMs = 0,
    )

    private fun mapped(row: InProgressRow): WidgetItem = WidgetMapping.toItem(row)

    @Test
    fun `an ordinary book maps to what the widget shows`() {
        val item = mapped(row())

        assertThat(item.title).isEqualTo("Lighthouse Wakes")
        assertThat(item.author).isEqualTo("James T. R. Corven")
        assertThat(item.progressFraction).isWithin(0.001f).of(600f / 3_600f)
        assertThat(item.remainingSeconds).isEqualTo(3_000.0)
    }

    @Test
    fun `a position past the end cannot draw a bar wider than itself`() {
        val item = mapped(row(positionSec = 4_000.0, durationSec = 3_600.0, progressFraction = 1.2))

        assertThat(item.progressFraction).isEqualTo(1f)
        assertThat(item.remainingSeconds).isEqualTo(0.0)
    }

    @Test
    fun `a negative fraction cannot draw a bar either`() {
        val item = mapped(row(progressFraction = -0.5))

        assertThat(item.progressFraction).isEqualTo(0f)
    }

    @Test
    fun `a book with no author named leaves the line out rather than saying unknown`() {
        val item = mapped(row(author = null))

        assertThat(item.author).isNull()
    }

    @Test
    fun `a book at the very start still reports its whole length as left`() {
        val item = mapped(row(positionSec = 0.0, progressFraction = 0.0))

        assertThat(item.remainingSeconds).isEqualTo(3_600.0)
        assertThat(item.progressFraction).isEqualTo(0f)
    }
}
