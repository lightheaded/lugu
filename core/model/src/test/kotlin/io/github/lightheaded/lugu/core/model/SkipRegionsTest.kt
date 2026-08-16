package io.github.lightheaded.lugu.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The show used throughout is invented, like every other example in this repo: *The
 * Tidewatch Hour*, which opens with a fifteen-second sting and closes with a thirty-second
 * plug for its own back catalogue.
 */
class SkipRegionsTest {

    private fun chapter(index: Int, start: Double, end: Double, title: String) =
        Chapter(id = index, startSec = start, endSec = end, title = title)

    @Test
    fun `nothing set means nothing skipped, and no work done`() {
        assertThat(SkipRegions.forEpisode(1800.0, trim = PodcastTrim.NONE)).isEmpty()
    }

    @Test
    fun `an intro is measured from the start and an outro from the end`() {
        val regions = SkipRegions.forEpisode(
            durationSec = 1800.0,
            trim = PodcastTrim(introSec = 15, outroSec = 30),
        )

        assertThat(regions).containsExactly(
            SkipRegion(0.0, 15.0, SkipReason.INTRO),
            SkipRegion(1770.0, 1800.0, SkipReason.OUTRO),
        ).inOrder()
    }

    /**
     * A feed that has not reported a duration yet is common enough — the episode list
     * arrives before the file is probed. The intro is still placeable from the start;
     * the outro is not placeable at all, and guessing where the end is would put the skip
     * somewhere in the middle of the show.
     */
    @Test
    fun `with no duration the intro still applies and the outro is left out`() {
        val regions = SkipRegions.forEpisode(
            durationSec = 0.0,
            trim = PodcastTrim(introSec = 15, outroSec = 30),
        )

        assertThat(regions).containsExactly(SkipRegion(0.0, 15.0, SkipReason.INTRO))
    }

    @Test
    fun `a chapter that names itself as advertising is skipped`() {
        val regions = SkipRegions.forEpisode(
            durationSec = 1800.0,
            chapters = listOf(
                chapter(0, 0.0, 600.0, "The Estuary Question"),
                chapter(1, 600.0, 690.0, "Advertisement"),
                chapter(2, 690.0, 1800.0, "Letters"),
            ),
            trim = PodcastTrim(skipMarkedAdverts = true),
        )

        assertThat(regions).containsExactly(SkipRegion(600.0, 690.0, SkipReason.ADVERT))
    }

    /**
     * The failure that matters in the other direction. A substring match on "ad" or
     * "promo" skips real chapters, and a skip that removes narration is worse than an
     * advert that plays — so matching is on the whole title, with punctuation ignored.
     */
    @Test
    fun `a chapter that merely contains those letters is left alone`() {
        assertThat(SkipRegions.isAdvert("Ad")).isTrue()
        assertThat(SkipRegions.isAdvert("[Ad]")).isTrue()
        assertThat(SkipRegions.isAdvert("Sponsor Message")).isTrue()
        assertThat(SkipRegions.isAdvert("ADVERTISEMENT")).isTrue()

        assertThat(SkipRegions.isAdvert("Adam's Return")).isFalse()
        assertThat(SkipRegions.isAdvert("Broad Strokes")).isFalse()
        assertThat(SkipRegions.isAdvert("A Promotion at the Harbour Office")).isFalse()
        assertThat(SkipRegions.isAdvert("Reading the Tides")).isFalse()
    }

    @Test
    fun `adverts are only looked for when the show asked for it`() {
        val chapters = listOf(chapter(0, 600.0, 690.0, "Advertisement"))

        assertThat(
            SkipRegions.forEpisode(1800.0, chapters, PodcastTrim(introSec = 15)),
        ).containsExactly(SkipRegion(0.0, 15.0, SkipReason.INTRO))
    }

    /**
     * Two jumps a fraction of a second apart is a stutter, not two skips. Regions that
     * touch — or nearly touch, which is what a chapter boundary from a feed looks like
     * after rounding — become one.
     */
    @Test
    fun `regions that touch merge into a single jump`() {
        val regions = SkipRegions.forEpisode(
            durationSec = 1800.0,
            chapters = listOf(
                chapter(0, 600.0, 690.0, "Advertisement"),
                chapter(1, 690.2, 730.0, "Promo"),
            ),
            trim = PodcastTrim(skipMarkedAdverts = true),
        )

        assertThat(regions).containsExactly(SkipRegion(600.0, 730.0, SkipReason.ADVERT))
    }

    @Test
    fun `an intro that overlaps a marked advert is one region, not two`() {
        val regions = SkipRegions.forEpisode(
            durationSec = 1800.0,
            chapters = listOf(chapter(0, 10.0, 90.0, "Sponsor")),
            trim = PodcastTrim(introSec = 15, skipMarkedAdverts = true),
        )

        assertThat(regions).hasSize(1)
        assertThat(regions.single().startSec).isEqualTo(0.0)
        assertThat(regions.single().endSec).isEqualTo(90.0)
        // The intro sorts first, so it names the merged region — which is also the
        // boundary the listener crosses when the episode starts.
        assertThat(regions.single().reason).isEqualTo(SkipReason.INTRO)
    }

    /**
     * The guard that keeps a setting from looking like a broken file. A show that once
     * published a forty-second trailer, against an intro set for its ordinary hour-long
     * episodes, would otherwise skip that episode from end to end — and an episode that
     * finishes the instant it starts does not read as a trim working.
     */
    @Test
    fun `a trim that would swallow a short episode is dropped entirely`() {
        val regions = SkipRegions.forEpisode(
            durationSec = 40.0,
            trim = PodcastTrim(introSec = 30, outroSec = 30),
        )

        assertThat(regions).isEmpty()
    }

    @Test
    fun `a trim longer than the episode cannot run past its end`() {
        val regions = SkipRegions.forEpisode(
            durationSec = 600.0,
            trim = PodcastTrim(introSec = 900),
        )

        // Clamped to the episode, then dropped because nothing would be left to play.
        assertThat(regions).isEmpty()
    }

    @Test
    fun `a position outside every region is left alone`() {
        val regions = SkipRegions.forEpisode(1800.0, trim = PodcastTrim(introSec = 15))

        assertThat(SkipRegions.outcomeFrom(20.0, regions, 1800.0)).isNull()
        assertThat(SkipRegions.outcomeFrom(15.0, regions, 1800.0)).isNull()
    }

    @Test
    fun `a position inside a region seeks to its end`() {
        val regions = SkipRegions.forEpisode(1800.0, trim = PodcastTrim(introSec = 15))

        val outcome = SkipRegions.outcomeFrom(0.0, regions, 1800.0)

        assertThat(outcome).isEqualTo(
            SkipRegions.SkipOutcome.SeekTo(15.0, SkipRegion(0.0, 15.0, SkipReason.INTRO)),
        )
    }

    /**
     * The distinction the outcome type exists for. Seeking to the last sample of an
     * episode is not the same as finishing it: the queue does not advance, continuation
     * does not fire, and the progress row never gets its finished flag — so the episode
     * sits at the very end, unfinished, forever.
     */
    @Test
    fun `an outro at the tail ends the episode rather than seeking to its last sample`() {
        val regions = SkipRegions.forEpisode(1800.0, trim = PodcastTrim(outroSec = 30))

        val outcome = SkipRegions.outcomeFrom(1775.0, regions, 1800.0)

        assertThat(outcome).isInstanceOf(SkipRegions.SkipOutcome.EndOfItem::class.java)
        assertThat(outcome?.skipped?.reason).isEqualTo(SkipReason.OUTRO)
    }

    @Test
    fun `with no duration to compare against, a skip is always a seek`() {
        val regions = listOf(SkipRegion(600.0, 690.0, SkipReason.ADVERT))

        assertThat(SkipRegions.outcomeFrom(650.0, regions))
            .isEqualTo(SkipRegions.SkipOutcome.SeekTo(690.0, regions.single()))
    }

    /**
     * Merging handles regions that touch; this handles the ones separated by a hair, which
     * is what two chapter boundaries from a feed look like after rounding. Either way the
     * listener gets one jump, and is told about the region they actually crossed.
     */
    @Test
    fun `a run of regions resolves in one jump and reports the one entered`() {
        val regions = listOf(
            SkipRegion(600.0, 690.0, SkipReason.ADVERT),
            SkipRegion(690.4, 730.0, SkipReason.ADVERT),
        )

        val outcome = SkipRegions.outcomeFrom(605.0, regions, 1800.0)

        assertThat(outcome).isEqualTo(
            SkipRegions.SkipOutcome.SeekTo(730.0, regions.first()),
        )
    }

    @Test
    fun `an advert running to the end of the episode ends it`() {
        val regions = listOf(SkipRegion(1700.0, 1800.0, SkipReason.ADVERT))

        assertThat(SkipRegions.outcomeFrom(1750.0, regions, 1800.0))
            .isInstanceOf(SkipRegions.SkipOutcome.EndOfItem::class.java)
    }
}
