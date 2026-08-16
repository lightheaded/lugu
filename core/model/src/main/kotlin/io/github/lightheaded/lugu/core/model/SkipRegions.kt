package io.github.lightheaded.lugu.core.model

/**
 * A stretch of audio to be jumped over, in whole-item seconds.
 *
 * Always half-open — `startSec` is inside, `endSec` is not — so two regions that touch do
 * not overlap by one sample, and a position exactly on a boundary belongs to exactly one
 * of them.
 */
data class SkipRegion(
    val startSec: Double,
    val endSec: Double,
    val reason: SkipReason,
) {
    val lengthSec: Double get() = (endSec - startSec).coerceAtLeast(0.0)

    fun contains(positionSec: Double): Boolean = positionSec >= startSec && positionSec < endSec
}

/** Why a region is being skipped — the word a notice uses, and the word the diary records. */
enum class SkipReason(val label: String) {
    INTRO("intro"),
    OUTRO("outro"),
    ADVERT("advert"),
}

/**
 * What a podcast trims from each of its episodes.
 *
 * Per podcast rather than global because the whole point is that *this* show has a
 * fifteen-second sting, and a listener who subscribes to twelve shows should set it once
 * per show and never again. Stored alongside the per-podcast speed, and for the same
 * reason: Audiobookshelf has nowhere to put it, so a client that wants it has to remember
 * it itself.
 *
 * Zero means "do not trim", which is also the default. Nothing here is inferred, guessed
 * or detected — every number was typed by the person who got tired of hearing it.
 */
data class PodcastTrim(
    val introSec: Int = 0,
    val outroSec: Int = 0,
    /** Whether chapters that name themselves as advertising are skipped for this show. */
    val skipMarkedAdverts: Boolean = false,
) {
    val isEmpty: Boolean get() = introSec <= 0 && outroSec <= 0 && !skipMarkedAdverts

    companion object {
        val NONE = PodcastTrim()

        /** Offered as one-tap choices; any value in range can still be typed. */
        val TRIM_CHOICES_SEC = listOf(0, 5, 10, 15, 20, 30, 45, 60, 90)
        const val MAX_TRIM_SEC = 600
    }
}

/**
 * Which regions of one episode get skipped, and why.
 *
 * ### Intros, outros and adverts are one mechanism, but only just
 *
 * At the point of playing they are identical: a sorted list of regions, and a rule that
 * says a position landing inside one jumps to its end. That is why they are computed
 * together here rather than as three features.
 *
 * Where they differ is entirely in where the region *comes from*, and the difference is
 * worth being blunt about because it decides what can honestly be promised:
 *
 *  - An **intro** and an **outro** are fixed offsets from the ends of the episode. The
 *    same sting opens every episode of a show, so one number covers all of them forever.
 *  - An **advert** is somewhere in the middle, at a different place in every episode, and
 *    of a different length. No fixed offset can find one.
 *
 * So adverts are only skippable where the episode *says* where they are — a chapter whose
 * title names it as advertising. A useful number of shows do ship those markers, because
 * the same markers drive the chapter list in every podcast app; lugu already parses them
 * for the chapter list, so this costs a title match and nothing else.
 *
 * What is deliberately **not** attempted is finding an unmarked advert. That needs audio
 * fingerprinting against a database of known adverts — a different kind of program, with
 * a network service behind it, and a false positive silently eats a minute of the show.
 * A skip that removes narration is worse than an advert that plays.
 *
 * ### Rules that keep this from eating the episode
 *
 * Regions are clamped to the episode, merged where they touch, and dropped if they would
 * consume it whole. A malformed feed reporting a two-second episode, or an intro longer
 * than the episode itself, otherwise produces a skip straight to the end — which looks
 * exactly like the app refusing to play.
 */
object SkipRegions {

    /**
     * Chapter titles that mean advertising.
     *
     * Matched on the whole title, lowercased, with punctuation ignored, so "Advertisement",
     * "[Ad]" and "Sponsor Message" all match while "Adam's Return" and "Broad Strokes" do
     * not — a substring match on "ad" would skip both of those, and skipping a real chapter
     * is the failure that matters here.
     */
    private val ADVERT_TITLES = setOf(
        "ad",
        "ads",
        "ad break",
        "ad breaks",
        "advert",
        "adverts",
        "advertising",
        "advertisement",
        "advertisements",
        "commercial",
        "commercials",
        "promo",
        "promos",
        "promotion",
        "sponsor",
        "sponsors",
        "sponsored",
        "sponsor message",
        "sponsor break",
        "sponsorship",
    )

    /** Everything that is not a letter, a digit or a single separating space. */
    private val NOISE = Regex("""[^a-z0-9 ]""")
    private val RUNS_OF_SPACE = Regex("""\s+""")

    /**
     * The regions to skip in one episode, sorted, merged and clamped.
     *
     * @param durationSec the episode's own duration. Zero or less means the duration is not
     *   known yet, in which case the outro cannot be placed and is left out rather than
     *   guessed at — the intro and any marked adverts still apply.
     */
    fun forEpisode(
        durationSec: Double,
        chapters: List<Chapter> = emptyList(),
        trim: PodcastTrim = PodcastTrim.NONE,
    ): List<SkipRegion> {
        if (trim.isEmpty) return emptyList()

        val raw = buildList {
            if (trim.introSec > 0) {
                add(SkipRegion(0.0, trim.introSec.toDouble(), SkipReason.INTRO))
            }
            if (trim.outroSec > 0 && durationSec > 0) {
                add(SkipRegion(durationSec - trim.outroSec, durationSec, SkipReason.OUTRO))
            }
            if (trim.skipMarkedAdverts) {
                chapters.filter { isAdvert(it.title) }
                    .forEach { add(SkipRegion(it.startSec, it.endSec, SkipReason.ADVERT)) }
            }
        }

        return merge(clamp(raw, durationSec))
    }

    /** True when a chapter title names itself as advertising rather than as content. */
    fun isAdvert(title: String): Boolean = normalise(title) in ADVERT_TITLES

    /**
     * What to do about a position, which is one of three things rather than a number.
     *
     * Two of the three would collapse into "seek here" and be wrong. A trailing outro's
     * destination *is* the end of the episode, and seeking to the last sample of an item
     * is not the same as finishing it: the queue does not advance, continuation does not
     * fire, and the progress row never gets its finished flag — the episode sits at 100%
     * and unfinished forever. So the end is its own outcome, and the caller ends the item
     * through whatever path a natural ending already takes.
     */
    sealed interface SkipOutcome {
        /** The region that triggered this, for the notice and for the diary. */
        val skipped: SkipRegion

        data class SeekTo(val positionSec: Double, override val skipped: SkipRegion) : SkipOutcome

        data class EndOfItem(override val skipped: SkipRegion) : SkipOutcome
    }

    /**
     * What should happen if playback has landed inside a region, or null if it has not.
     *
     * Resolves through a *run* of regions, so an advert immediately followed by a promo is
     * one jump rather than two. Merging already joins regions that touch; this covers the
     * case where a rounding error left a hair's gap between them, which happens with
     * chapter boundaries that came from a feed rather than from a file.
     *
     * @param durationSec the episode's duration, used only to recognise that a destination
     *   has reached the end. Zero or less means it is unknown, in which case a seek is
     *   returned — with no duration there is no end to have reached.
     */
    fun outcomeFrom(
        positionSec: Double,
        regions: List<SkipRegion>,
        durationSec: Double = 0.0,
    ): SkipOutcome? {
        val entered = regions.firstOrNull { it.contains(positionSec) } ?: return null
        var landed = entered
        var moved = true
        while (moved) {
            moved = false
            val next = regions.firstOrNull {
                it.startSec > landed.startSec && it.startSec <= landed.endSec + JOIN_TOLERANCE_SEC
            }
            if (next != null && next.endSec > landed.endSec) {
                landed = next
                moved = true
            }
        }
        // The reason reported is the region actually crossed, not the last one in the run:
        // a listener who hit an advert should be told an advert was skipped, even if the
        // jump carried on through the outro behind it.
        val reached = durationSec > 0 && landed.endSec >= durationSec - JOIN_TOLERANCE_SEC
        return if (reached) {
            SkipOutcome.EndOfItem(entered)
        } else {
            SkipOutcome.SeekTo(landed.endSec, entered)
        }
    }

    private fun normalise(title: String): String =
        title.lowercase()
            .replace(NOISE, " ")
            .replace(RUNS_OF_SPACE, " ")
            .trim()

    /**
     * Holds every region inside the episode, and refuses one that would swallow it.
     *
     * The [MIN_REMAINING_SEC] floor is the guard that matters: an intro of sixty seconds
     * set against a show that once published a forty-second trailer would otherwise skip
     * that episode entirely, and an episode that ends the instant it starts reads as a
     * broken file rather than as a setting doing its job.
     */
    private fun clamp(regions: List<SkipRegion>, durationSec: Double): List<SkipRegion> {
        val end = if (durationSec > 0) durationSec else Double.MAX_VALUE
        val held = regions.mapNotNull { region ->
            val start = region.startSec.coerceIn(0.0, end)
            val stop = region.endSec.coerceIn(0.0, end)
            if (stop - start <= 0.0) null else region.copy(startSec = start, endSec = stop)
        }
        if (durationSec <= 0) return held
        val total = merge(held).sumOf { it.lengthSec }
        return if (durationSec - total < MIN_REMAINING_SEC) emptyList() else held
    }

    /** Overlapping and touching regions become one, so a jump is never taken in stages. */
    private fun merge(regions: List<SkipRegion>): List<SkipRegion> {
        if (regions.size < 2) return regions
        val sorted = regions.sortedBy { it.startSec }
        val merged = mutableListOf(sorted.first())
        for (region in sorted.drop(1)) {
            val last = merged.last()
            if (region.startSec <= last.endSec + JOIN_TOLERANCE_SEC) {
                merged[merged.lastIndex] = last.copy(
                    endSec = maxOf(last.endSec, region.endSec),
                    // The first reason wins the merged region, because it is the one whose
                    // boundary the listener actually crossed and so the one a notice
                    // naming the skip should say.
                    reason = last.reason,
                )
            } else {
                merged += region
            }
        }
        return merged
    }

    /** A hair, to absorb the rounding between a chapter's end and the next one's start. */
    private const val JOIN_TOLERANCE_SEC = 0.5

    /** An episode trimmed to less than this is not worth playing, so nothing is trimmed. */
    private const val MIN_REMAINING_SEC = 5.0
}
