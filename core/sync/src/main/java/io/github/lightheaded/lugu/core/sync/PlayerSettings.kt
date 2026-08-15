package io.github.lightheaded.lugu.core.sync

/** A button that can appear in the player, the notification, or both. */
enum class TransportButton(val id: String, val label: String) {
    SKIP_BACK("skip_back", "Skip back"),
    SKIP_FORWARD("skip_forward", "Skip forward"),
    PREVIOUS_CHAPTER("prev_chapter", "Previous chapter"),
    NEXT_CHAPTER("next_chapter", "Next chapter"),
    ;

    companion object {
        fun fromId(id: String): TransportButton? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How a playback speed is chosen for an item.
 *
 * The per-podcast case is keyed on the podcast, not the episode: a listener picks a
 * speed for a *narrator*, and a podcast's narrator is the same across its episodes.
 * Remembering per episode would mean setting it again every week.
 */
data class SpeedSettings(
    val defaultSpeed: Float = 1.0f,
    /** When true, podcasts use [defaultPodcastSpeed] instead of [defaultSpeed]. */
    val separatePodcastSpeed: Boolean = false,
    val defaultPodcastSpeed: Float = 1.2f,
    val rememberPerBook: Boolean = true,
    val rememberPerPodcast: Boolean = true,
    val presets: List<Float> = DEFAULT_PRESETS,
) {
    companion object {
        val DEFAULT_PRESETS = listOf(1.0f, 1.2f, 1.5f, 1.8f, 2.0f)
        const val MIN = 0.5f
        const val MAX = 3.5f
        const val STEP = 0.05f
    }
}

/**
 * Everything about the transport controls.
 *
 * Defaults follow the observed usage order: seeking back to catch a missed sentence is
 * the dominant action, finding a place is second, and chapter skipping is occasional.
 * So the seek pair is always on and prominent, and the chapter pair is optional and
 * secondary — including in the notification, where space is scarcest.
 */
data class PlayerSettings(
    val skipBackSec: Int = 15,
    val skipForwardSec: Int = 30,
    /** Buttons shown in the player screen, in addition to the always-present seek pair. */
    val playerButtons: Set<TransportButton> = setOf(
        TransportButton.SKIP_BACK,
        TransportButton.SKIP_FORWARD,
        TransportButton.PREVIOUS_CHAPTER,
        TransportButton.NEXT_CHAPTER,
    ),
    /** Buttons offered to the notification and lock screen. */
    val notificationButtons: Set<TransportButton> = setOf(
        TransportButton.SKIP_BACK,
        TransportButton.SKIP_FORWARD,
    ),
    val speed: SpeedSettings = SpeedSettings(),
    /**
     * How long an automatic-correction notice stays up.
     *
     * These notices exist because a silent correction is indistinguishable from the app
     * losing someone's place — but they also carry an Undo, so they have to stay long
     * enough to be read and acted on. Ten seconds is the default; Material's own "long"
     * duration is the same, and four seconds is not enough to read a timestamp and
     * decide.
     */
    val noticeSeconds: Int = 10,
) {
    val showsChapterButtonsInPlayer: Boolean
        get() = TransportButton.PREVIOUS_CHAPTER in playerButtons ||
            TransportButton.NEXT_CHAPTER in playerButtons

    val showsChapterButtonsInNotification: Boolean
        get() = TransportButton.PREVIOUS_CHAPTER in notificationButtons ||
            TransportButton.NEXT_CHAPTER in notificationButtons

    companion object {
        /** Offered as one-tap choices; any value in range can still be set. */
        val SKIP_CHOICES = listOf(5, 10, 15, 20, 30, 45, 60, 90)

        val NOTICE_CHOICES = listOf(4, 7, 10, 15, 30)
    }
}
