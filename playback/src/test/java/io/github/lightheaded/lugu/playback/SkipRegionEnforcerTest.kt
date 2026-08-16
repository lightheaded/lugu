package io.github.lightheaded.lugu.playback

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.Chapter
import io.github.lightheaded.lugu.core.model.PodcastTrim
import io.github.lightheaded.lugu.core.model.SkipReason
import org.junit.Test

/**
 * The four ways an automatic skip goes wrong, pinned one test at a time.
 *
 * It fires on something it has no business touching; it fires twice and is heard as a
 * stutter; it cannot be overridden, so the listener who wanted to hear the theme is stuck in
 * a loop; or it ends an episode by seeking to the last sample and loses the finished flag.
 * None of the four can be seen by reading the class, because all four are about *when* a
 * decision is taken rather than what it says, and the position only arrives twice a second.
 *
 * The show throughout is "The Lamplighter's Hour", a podcast invented for these tests, whose
 * episodes open with a fifteen-second sting and close with thirty seconds of credits.
 */
class SkipRegionEnforcerTest {

    private val trim = PodcastTrim(introSec = 15, outroSec = 30, skipMarkedAdverts = true)

    private fun plan(
        durationSec: Double = 1800.0,
        chapters: List<Chapter> = emptyList(),
        trim: PodcastTrim = this.trim,
        announces: Boolean = true,
        episodeId: String? = "ep-1",
    ) = SkipPlan.forEpisode(
        libraryItemId = "show-lamplighter",
        episodeId = episodeId,
        durationSec = durationSec,
        chapters = chapters,
        trim = trim,
        announces = announces,
    )

    private fun advertChapters() = listOf(
        Chapter(id = 0, startSec = 0.0, endSec = 600.0, title = "The long night"),
        Chapter(id = 1, startSec = 600.0, endSec = 660.0, title = "Sponsor message"),
        Chapter(id = 2, startSec = 660.0, endSec = 1800.0, title = "Morning"),
    )

    // Only podcasts.

    /**
     * The rule that matters most, and the only one enforced by the type rather than by a
     * branch: a book's chapters are its content, and an intro offset applied to one is
     * forty seconds of narration gone.
     */
    @Test
    fun `a book cannot be given a plan at all`() {
        assertThat(plan(episodeId = null)).isNull()
    }

    @Test
    fun `a show with no trim set has nothing to enforce`() {
        assertThat(plan(trim = PodcastTrim.NONE)).isNull()
    }

    @Test
    fun `nothing loaded decides nothing`() {
        assertThat(SkipRegionEnforcer().decide(null, positionSec = 3.0, isPlaying = true)).isNull()
    }

    // The skip itself.

    @Test
    fun `playing into the intro jumps to the end of it`() {
        val decision = SkipRegionEnforcer().decide(plan(), positionSec = 0.4, isPlaying = true)

        assertThat(decision).isInstanceOf(SkipDecision.Seek::class.java)
        val seek = decision as SkipDecision.Seek
        assertThat(seek.toPositionSec).isEqualTo(15.0)
        assertThat(seek.skipped.reason).isEqualTo(SkipReason.INTRO)
    }

    @Test
    fun `a marked advert in the middle is skipped and named as an advert`() {
        val decision = SkipRegionEnforcer()
            .decide(plan(chapters = advertChapters()), positionSec = 600.2, isPlaying = true)

        assertThat(decision).isInstanceOf(SkipDecision.Seek::class.java)
        assertThat((decision as SkipDecision.Seek).toPositionSec).isEqualTo(660.0)
        assertThat(decision.skipped.reason).isEqualTo(SkipReason.ADVERT)
    }

    @Test
    fun `a position outside every region is left alone`() {
        assertThat(SkipRegionEnforcer().decide(plan(), positionSec = 900.0, isPlaying = true))
            .isNull()
    }

    /**
     * A paused listener is looking at the seek bar. Moving the position under their thumb is
     * the app arguing with the hand that is on it, and it looks like a fault rather than a
     * feature.
     */
    @Test
    fun `a paused listener sitting in the intro is not moved`() {
        val enforcer = SkipRegionEnforcer()
        val plan = plan()

        assertThat(enforcer.decide(plan, positionSec = 3.0, isPlaying = false)).isNull()
        assertThat(enforcer.decide(plan, positionSec = 3.0, isPlaying = true)).isNotNull()
    }

    // On entry, once.

    /** Half a second is easily long enough for a seek not to have shown up yet. */
    @Test
    fun `a second tick still inside the region does not skip again`() {
        val enforcer = SkipRegionEnforcer()
        val plan = plan()

        assertThat(enforcer.decide(plan, positionSec = 0.4, isPlaying = true)).isNotNull()
        assertThat(enforcer.decide(plan, positionSec = 0.9, isPlaying = true)).isNull()
        assertThat(enforcer.decide(plan, positionSec = 1.4, isPlaying = true)).isNull()
    }

    @Test
    fun `once past the region the ticks that follow decide nothing`() {
        val enforcer = SkipRegionEnforcer()
        val plan = plan()

        enforcer.decide(plan, positionSec = 0.4, isPlaying = true)
        assertThat(enforcer.decide(plan, positionSec = 15.0, isPlaying = true)).isNull()
        assertThat(enforcer.decide(plan, positionSec = 15.5, isPlaying = true)).isNull()
    }

    /**
     * Each region is entered on its own merits. Having skipped the intro must not use up the
     * one decision the episode is allowed.
     */
    @Test
    fun `an advert later in the episode is still skipped after the intro was`() {
        val enforcer = SkipRegionEnforcer()
        val plan = plan(chapters = advertChapters())

        enforcer.decide(plan, positionSec = 0.4, isPlaying = true)
        enforcer.onSeek(plan, toPositionSec = 15.0)

        val decision = enforcer.decide(plan, positionSec = 600.2, isPlaying = true)
        assertThat((decision as SkipDecision.Seek).toPositionSec).isEqualTo(660.0)
    }

    // A deliberate seek wins.

    @Test
    fun `scrubbing back into the intro is not undone`() {
        val enforcer = SkipRegionEnforcer()
        val plan = plan()

        enforcer.onSeek(plan, toPositionSec = 4.0)

        assertThat(enforcer.decide(plan, positionSec = 4.0, isPlaying = true)).isNull()
        assertThat(enforcer.decide(plan, positionSec = 4.5, isPlaying = true)).isNull()
        assertThat(enforcer.decide(plan, positionSec = 14.9, isPlaying = true)).isNull()
    }

    /**
     * The override is about the intent that put them there, not about the region for the rest
     * of the episode — an episode played twice through in one sitting still gets its intro
     * trimmed the second time.
     */
    @Test
    fun `drifting back in from outside after an override skips again`() {
        val enforcer = SkipRegionEnforcer()
        val plan = plan()

        enforcer.onSeek(plan, toPositionSec = 4.0)
        assertThat(enforcer.decide(plan, positionSec = 4.0, isPlaying = true)).isNull()
        // Played out of the intro, which is what lifts the exemption.
        assertThat(enforcer.decide(plan, positionSec = 20.0, isPlaying = true)).isNull()
        // And back in, by a seek to the very start rather than by drifting — but the point is
        // the same: this entry was not the one that was overridden.
        assertThat(enforcer.decide(plan, positionSec = 2.0, isPlaying = true)).isNotNull()
    }

    /**
     * Undo seeks back to where the listener was, which is inside the region just skipped. If
     * that seek were treated as drift the enforcer would skip again half a second later and
     * the Undo button would appear to do nothing at all.
     */
    @Test
    fun `undoing a skip is not immediately re-skipped`() {
        val enforcer = SkipRegionEnforcer()
        val plan = plan()

        val decision = enforcer.decide(plan, positionSec = 0.4, isPlaying = true)
        val undo = (decision as SkipDecision.Seek).undo!!
        enforcer.onSeek(plan, toPositionSec = decision.toPositionSec)

        enforcer.onSeek(plan, toPositionSec = undo.fromSec)
        assertThat(enforcer.decide(plan, positionSec = undo.fromSec, isPlaying = true)).isNull()
    }

    /**
     * Our own seek is consumed by the destination we asked for, so the *next* seek is read as
     * the listener's. Without that, one skip would swallow the override that follows it.
     */
    @Test
    fun `our own seek is consumed and the next one is still the listener's`() {
        val enforcer = SkipRegionEnforcer()
        val plan = plan(chapters = advertChapters())

        enforcer.decide(plan, positionSec = 600.2, isPlaying = true)
        enforcer.onSeek(plan, toPositionSec = 660.0)

        enforcer.onSeek(plan, toPositionSec = 610.0)
        assertThat(enforcer.decide(plan, positionSec = 610.0, isPlaying = true)).isNull()
    }

    // The end of the episode.

    @Test
    fun `a trailing outro ends the episode rather than seeking to its last sample`() {
        val decision = SkipRegionEnforcer().decide(plan(), positionSec = 1770.5, isPlaying = true)

        assertThat(decision).isInstanceOf(SkipDecision.EndItem::class.java)
        assertThat((decision as SkipDecision.EndItem).skipped.reason).isEqualTo(SkipReason.OUTRO)
    }

    /**
     * A player parked at its own duration is inside nothing, so the exemption lifts on the
     * next tick. Only the episode having been ended stops a second announcement of an episode
     * that is already over.
     */
    @Test
    fun `an episode is only ended once`() {
        val enforcer = SkipRegionEnforcer()
        val plan = plan()

        assertThat(enforcer.decide(plan, positionSec = 1770.5, isPlaying = true)).isNotNull()
        assertThat(enforcer.decide(plan, positionSec = 1771.0, isPlaying = true)).isNull()
        assertThat(enforcer.decide(plan, positionSec = 1800.0, isPlaying = true)).isNull()
    }

    @Test
    fun `seeking back into an ended episode arms its outro again`() {
        val enforcer = SkipRegionEnforcer()
        val plan = plan()

        enforcer.decide(plan, positionSec = 1770.5, isPlaying = true)
        enforcer.onSeek(plan, toPositionSec = 900.0)

        assertThat(enforcer.decide(plan, positionSec = 1771.0, isPlaying = true))
            .isInstanceOf(SkipDecision.EndItem::class.java)
    }

    /** With no duration there is no end to have reached, so the outro is not placed at all. */
    @Test
    fun `an episode of unknown duration still gets its intro trimmed`() {
        val decision = SkipRegionEnforcer()
            .decide(plan(durationSec = 0.0), positionSec = 1.0, isPlaying = true)

        assertThat((decision as SkipDecision.Seek).toPositionSec).isEqualTo(15.0)
    }

    // The notice.

    @Test
    fun `the undo carries the position actually left and the episode it belongs to`() {
        val decision = SkipRegionEnforcer()
            .decide(plan(), positionSec = 0.4, isPlaying = true) as SkipDecision.Seek

        val undo = decision.undo!!
        assertThat(undo.libraryItemId).isEqualTo("show-lamplighter")
        assertThat(undo.episodeId).isEqualTo("ep-1")
        assertThat(undo.fromSec).isEqualTo(0.4)
        assertThat(undo.toSec).isEqualTo(15.0)
    }

    /** The end of an episode is announced too; its undo returns to where the credits caught them. */
    @Test
    fun `ending an episode still offers an undo`() {
        val decision = SkipRegionEnforcer()
            .decide(plan(), positionSec = 1770.5, isPlaying = true) as SkipDecision.EndItem

        val undo = decision.undo!!
        assertThat(undo.fromSec).isEqualTo(1770.5)
        assertThat(undo.toSec).isEqualTo(1800.0)
    }

    /**
     * Turning announcing off silences the notice and nothing else. The skip is the feature;
     * the notice is the promise that it is visible, and someone who has read the setting and
     * turned it off has released the app from that promise.
     */
    @Test
    fun `announcing off still skips, and offers nothing to undo`() {
        val decision = SkipRegionEnforcer()
            .decide(plan(announces = false), positionSec = 0.4, isPlaying = true)

        assertThat((decision as SkipDecision.Seek).toPositionSec).isEqualTo(15.0)
        assertThat(decision.undo).isNull()
    }

    // Moving on.

    /**
     * The memory is about one episode. Carrying an exemption into the next one would hand it
     * an intro nobody asked to hear.
     */
    @Test
    fun `a new episode starts with a clean memory`() {
        val enforcer = SkipRegionEnforcer()
        val first = plan()
        val second = SkipPlan.forEpisode(
            libraryItemId = "show-lamplighter",
            episodeId = "ep-2",
            durationSec = 1800.0,
            chapters = emptyList(),
            trim = trim,
            announces = true,
        )

        enforcer.onSeek(first, toPositionSec = 4.0)
        assertThat(enforcer.decide(first, positionSec = 4.0, isPlaying = true)).isNull()

        assertThat(enforcer.decide(second, positionSec = 4.0, isPlaying = true)).isNotNull()
    }

    /** A book played after an episode must not inherit anything at all. */
    @Test
    fun `a book following an episode decides nothing`() {
        val enforcer = SkipRegionEnforcer()
        val plan = plan()

        enforcer.decide(plan, positionSec = 0.4, isPlaying = true)
        assertThat(enforcer.decide(null, positionSec = 0.4, isPlaying = true)).isNull()
        // And the episode's own memory is gone with it, rather than waiting to be used on
        // whatever comes back.
        assertThat(enforcer.decide(plan, positionSec = 0.4, isPlaying = true)).isNotNull()
    }
}
