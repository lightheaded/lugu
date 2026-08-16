package io.github.lightheaded.lugu.playback

import io.github.lightheaded.lugu.core.model.Chapter
import io.github.lightheaded.lugu.core.model.PodcastTrim
import io.github.lightheaded.lugu.core.model.SkipReason
import io.github.lightheaded.lugu.core.model.SkipRegion
import io.github.lightheaded.lugu.core.model.SkipRegions
import io.github.lightheaded.lugu.core.sync.ProgressJump
import kotlin.math.abs

/**
 * One episode's skip regions, and the fact that it *is* an episode.
 *
 * `SkipRegions.forEpisode` will happily compute regions for anything with a duration and a
 * chapter list, which includes every book in the library. That would be a disaster of a
 * different order from a mistimed skip: a book's chapters are its content, an intro offset
 * against chapter one is forty seconds of narration, and the listener would have no idea
 * why their book began in the middle of a sentence. Documenting "podcasts only" is not
 * enough for something that destroys audio, so the rule is in the type instead — a plan is
 * the only thing [SkipRegionEnforcer] will act on, and [forEpisode] is the only way to get
 * one. It refuses anything without an episode id, which is how the rest of the service
 * already tells a podcast from a book (see the media type passed to `setSpeedFor`).
 *
 * A plan with no regions is refused for the same reason and to the same effect: the
 * overwhelming majority of what plays here is a book or a show nobody has set a trim on, and
 * for all of it the enforcer holds a null and does nothing at all.
 */
class SkipPlan private constructor(
    val libraryItemId: String,
    val episodeId: String,
    val durationSec: Double,
    val regions: List<SkipRegion>,
    /** Whether a skip announces itself and offers an undo. `SkipSettings.announceSkips`. */
    val announces: Boolean,
) {
    /** Identity of the thing being played, so a new episode starts with a clean memory. */
    internal val episodeKey: String get() = "$libraryItemId#$episodeId"

    /**
     * The jump to offer as an undo, or null when the listener has turned announcing off.
     *
     * A `ProgressJump` rather than a type of this file's own: the app already has exactly one
     * automatic-correction notice, it already carries an Undo, and it already speaks this.
     * Inventing a second notice would mean a second thing that can be missed, a second
     * timeout to keep in step with `PlayerSettings.noticeSeconds`, and two snackbars racing
     * each other on the one occasion both fire.
     */
    internal fun undoFor(fromSec: Double, toSec: Double, reason: SkipReason): ProgressJump? =
        if (announces) {
            ProgressJump(
                libraryItemId = libraryItemId,
                episodeId = episodeId,
                fromSec = fromSec,
                toSec = toSec,
                // Named, because "Jumped from 0:00 to 0:15" is a true account of an intro
                // being skipped that explains none of it — and a correction nobody can
                // account for reads as the app losing their place.
                reason = "Skipped the ${reason.label}",
            )
        } else {
            null
        }

    companion object {
        /**
         * The plan for one podcast episode, or null when there is nothing to enforce.
         *
         * @param episodeId null for a book, which is refused — see the class note.
         * @param trim this show's own trim, from `PlaybackPrefs.observeTrimFor`.
         */
        fun forEpisode(
            libraryItemId: String,
            episodeId: String?,
            durationSec: Double,
            chapters: List<Chapter>,
            trim: PodcastTrim,
            announces: Boolean,
        ): SkipPlan? {
            val episode = episodeId ?: return null
            val regions = SkipRegions.forEpisode(durationSec, chapters, trim)
            if (regions.isEmpty()) return null
            return SkipPlan(
                libraryItemId = libraryItemId,
                episodeId = episode,
                durationSec = durationSec,
                regions = regions,
                announces = announces,
            )
        }
    }
}

/**
 * What the caller should do about a position that has landed in a skip region.
 *
 * Both cases carry the region actually crossed, because the diary line and the notice both
 * want to name it, and both carry the undo the notice offers.
 */
sealed interface SkipDecision {
    /** The region the position entered — an advert, even if the jump ran on into an outro. */
    val skipped: SkipRegion

    /** The undo to hand to `PlaybackStateHolder.setUndoableJump`, or null when announcing is off. */
    val undo: ProgressJump?

    /** Seek to [toPositionSec], in whole-item seconds. */
    data class Seek(
        val toPositionSec: Double,
        override val skipped: SkipRegion,
        override val undo: ProgressJump?,
    ) : SkipDecision

    /** The skip reached the end of the episode; end it the way any episode ends. */
    data class EndItem(
        override val skipped: SkipRegion,
        override val undo: ProgressJump?,
    ) : SkipDecision
}

/**
 * Jumps a podcast episode over its intro, its outro and its marked adverts.
 *
 * `SkipRegions` says *where* the regions are; this says *when* one is acted on, which is a
 * different question and the one with all the ways of being wrong in it. It decides and
 * returns; it never touches the player. That is not tidiness — the seek has to go back
 * through `AbsoluteTiming` on a multi-file item, and every position lugu records, syncs or
 * resumes from is in whole-item seconds. A class that held a player would be a second place
 * where a raw player position could escape into a saved position, which is the worst class of
 * bug this app has.
 *
 * ### Where the position comes from, and why it is not a new timer
 *
 * Media3 offers no "tell me when the position passes X" callback for ordinary content. The
 * nearest thing is `ExoPlayer.createMessage` with a position set on it, which is genuinely
 * free — the playback thread delivers it, nothing wakes up to ask — but it has to be
 * re-posted after every seek, every discontinuity and every item change, it arrives on the
 * playback thread so every touch of the state holder would have to be posted back, and a
 * message whose position has already gone past is simply never delivered. That is a lot of
 * machinery whose only advantage is sub-frame precision, and sub-frame precision is worth
 * nothing here: the regions themselves are joined with half a second of tolerance, and a
 * skip that happens a beat into a fifteen-second sting is indistinguishable from one that
 * happened on the boundary.
 *
 * So the position is polled — but on the half-second loop the service already runs for the
 * sleep timer and the chapter metadata. That loop does not start for this, does not tick
 * faster for this, and does not keep the CPU awake for this. The marginal battery cost of
 * enforcement is one null check per tick against a field that is null for every book and
 * every untrimmed show, which is very nearly everything that ever plays. A dedicated ticker
 * would have cost a wakeup twice a second for a feature most listeners never turn on, and
 * battery drain nothing on screen explains is a standing complaint this app exists to avoid.
 *
 * ### On entry, once
 *
 * A decision is made when the position first lands inside a region and then not again until
 * the position has left it. Half a second is long enough for a seek to be issued and not yet
 * be visible in `currentPosition`, and a second decision taken in that gap would seek again,
 * announce again and write a second line in the diary — a jump heard as a stutter, and a
 * notice that flickers. So the region that was acted on is held as exempt, and the exemption
 * is lifted by the position being outside it rather than by a timeout, because a timeout
 * would be a guess about how long a seek takes on a phone that is also buffering.
 *
 * ### A deliberate seek wins
 *
 * Somebody who scrubs back into the intro means it — they want to hear the theme, or they
 * overshot and are feeling for a place. An enforcer that dragged them out again would be a
 * loop with no way out of it, and the only escape would be turning the feature off, which is
 * not an answer to "I wanted to hear that bit". So a seek that lands inside a region makes
 * that region exempt until playback leaves it, and playback that later drifts back in from
 * outside is skipped as normal — the exemption is about the intent that put them there, not
 * about the region forever.
 *
 * Drift and intent cannot be told apart from the position alone, so the caller reports seeks
 * through [onSeek] from the discontinuity it already listens to. That includes this class's
 * own skip seeks, which is why the destination last asked for is remembered and matched:
 * mistaking our own seek for the listener's would exempt the region we just left, and
 * mistaking the listener's for ours would put the enforcer straight back into the loop above.
 *
 * This is also the whole of what makes the Undo work. Undoing a skip seeks back to where the
 * listener was, which is inside the region that was skipped; without the override the seek
 * would be undone again half a second later and the Undo button would appear to do nothing.
 *
 * ### The end of the episode
 *
 * An outro at the tail of an episode has nowhere to seek to. `SkipRegions.outcomeFrom`
 * reports that as [SkipRegions.SkipOutcome.EndOfItem] rather than as a destination, and it is
 * passed through as [SkipDecision.EndItem] for the caller to route into the ordinary
 * end-of-item path. Seeking to the last sample instead would look identical and be quietly
 * wrong in three ways: whether the item then reports itself ended depends on rounding in the
 * duration, the finished flag rides on that, and the queue, the continuation and the progress
 * sync all hang off the end-of-item path rather than off the position. An episode that ends
 * without being marked finished is one that turns up again tomorrow.
 *
 * Once an episode has been ended this way it is not decided about again, however the position
 * moves afterwards. A player sitting at its own duration is still inside nothing, and without
 * this the tick that follows would announce a second skip of an episode that is already over.
 * A deliberate seek clears it, because seeking back into the episode is asking for it again.
 */
class SkipRegionEnforcer {

    /** The episode the memory below is about; a change of episode wipes it. */
    private var episodeKey: String? = null

    /** The region not to act on again until the position is outside it. */
    private var exempt: SkipRegion? = null

    /** Where the last skip asked to go, so that seek can be recognised as ours. */
    private var ownDestinationSec: Double? = null

    /** Whether this episode has already been ended by a trailing outro. */
    private var ended = false

    /**
     * What to do at [positionSec], in whole-item seconds, or null for "carry on".
     *
     * @param plan null for a book, for a show with no trim, and for nothing loaded — all
     *   three mean the same thing here, and all three cost one comparison.
     * @param isPlaying a paused listener is never moved. Someone who stopped inside the intro
     *   is looking at the seek bar, and a position that jumps while they are holding it is
     *   the app fighting the hand that is on it. The skip is taken on the first tick after
     *   they press play, which is where they will hear it as a skip rather than as a glitch.
     */
    fun decide(plan: SkipPlan?, positionSec: Double, isPlaying: Boolean): SkipDecision? {
        val current = adopt(plan) ?: return null
        if (!isPlaying || ended) return null

        exempt?.let { if (!it.contains(positionSec)) exempt = null }
        if (exempt != null) return null

        val outcome = SkipRegions.outcomeFrom(positionSec, current.regions, current.durationSec)
            ?: return null
        exempt = outcome.skipped

        return when (outcome) {
            is SkipRegions.SkipOutcome.SeekTo -> {
                ownDestinationSec = outcome.positionSec
                SkipDecision.Seek(
                    toPositionSec = outcome.positionSec,
                    skipped = outcome.skipped,
                    // From where they actually were rather than from the region's start: the
                    // undo has to return the second of narration they had already heard.
                    undo = current.undoFor(positionSec, outcome.positionSec, outcome.skipped.reason),
                )
            }

            is SkipRegions.SkipOutcome.EndOfItem -> {
                ended = true
                ownDestinationSec = null
                SkipDecision.EndItem(
                    skipped = outcome.skipped,
                    undo = current.undoFor(positionSec, current.durationSec, outcome.skipped.reason),
                )
            }
        }
    }

    /**
     * A seek has happened. Reported for every seek, including this class's own.
     *
     * The tolerance is a second — far tighter than the shortest trim anyone can choose, and
     * far looser than the few tens of milliseconds a seek can be moved by the sample the
     * renderer actually lands on. Being wrong in the generous direction costs nothing: the
     * destination of a skip is by construction outside every region, so a seek near it has
     * nothing to be exempted from.
     */
    fun onSeek(plan: SkipPlan?, toPositionSec: Double) {
        val current = adopt(plan) ?: return
        val expected = ownDestinationSec
        ownDestinationSec = null
        if (expected != null && abs(toPositionSec - expected) <= OWN_SEEK_TOLERANCE_SEC) return

        exempt = current.regions.firstOrNull { it.contains(toPositionSec) }
        // Seeking back into an episode that was ended by its own outro is asking to hear it
        // again, and its outro is then owed a second skip like any other region.
        ended = false
    }

    private fun adopt(plan: SkipPlan?): SkipPlan? {
        if (plan?.episodeKey != episodeKey) {
            episodeKey = plan?.episodeKey
            exempt = null
            ownDestinationSec = null
            ended = false
        }
        return plan
    }

    private companion object {
        /** How near our own destination a seek has to land to be recognised as ours. */
        const val OWN_SEEK_TOLERANCE_SEC = 1.0
    }
}
