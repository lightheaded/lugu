package io.github.lightheaded.lugu.playback

/**
 * How one part-heard thing reads as a row in a car.
 *
 * The Continue node used to group by item, so three part-heard episodes of one show became a
 * single row carrying the show's name — and picking either of the other two meant opening the
 * podcast and finding it, which is several glances at a screen while driving. Now there is a
 * row per episode, and a row per episode is only useful if it says which episode it is.
 *
 * So an episode names itself and lets the show be the subtitle, which is the reverse of the
 * podcast node where the show is already known. A book has no episode and keeps its own title
 * and its author. An episode the mirror has not seen yet falls back to the item's title rather
 * than to a blank row: a row that cannot be read is worse than one that is imprecise.
 */
internal object ContinueRows {

    fun title(itemTitle: String, episodeTitle: String?): String =
        episodeTitle?.takeIf { it.isNotBlank() } ?: itemTitle

    fun subtitle(itemTitle: String, author: String?, episodeTitle: String?): String? =
        if (episodeTitle.isNullOrBlank()) author else itemTitle
}
