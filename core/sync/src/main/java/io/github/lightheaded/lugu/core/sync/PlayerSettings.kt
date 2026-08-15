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
 * What a headset's previous and next buttons do.
 *
 * They exist because a headset has three buttons and a book has no tracks, so "next" has
 * to mean something chosen rather than something obvious. Media3's own default is to move
 * to the next media item, which on a single-file book means seeking to zero — the bug that
 * once cost a forty-hour book its position and the reason any of this is configurable.
 */
enum class HeadsetAction(val id: String, val label: String) {
    SKIP("skip", "Skip by the set seconds"),
    CHAPTER("chapter", "Jump a chapter"),
    ITEM("item", "Next or previous item"),
    NOTHING("nothing", "Nothing"),
    ;

    companion object {
        fun fromId(id: String?): HeadsetAction? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Defaults to skipping in both directions, matching the observed usage order: seeking back
 * to catch a missed sentence is the dominant action, and a headset button is the control
 * most often pressed without looking.
 */
data class HeadsetSettings(
    val nextAction: HeadsetAction = HeadsetAction.SKIP,
    val previousAction: HeadsetAction = HeadsetAction.SKIP,
)

/**
 * What is done to the audio itself.
 *
 * Both are off by default because both change what the listener hears. Silence skipping
 * is the one people ask for by name — a badly mastered recording can be a third silence —
 * but it also clips the pauses a narrator put there on purpose, so it is a choice rather
 * than a kindness. The boost exists for the opposite problem: a quiet recording that is
 * inaudible in a car at motorway speed.
 */
data class AudioSettings(
    val skipSilence: Boolean = false,
    /**
     * Extra gain in decibels, applied by the platform's loudness enhancer.
     *
     * Capped well below what the API allows. Gain is not free — past a point it is
     * distortion rather than volume — and a setting that can make a book unlistenable
     * is not a setting worth having.
     */
    val volumeBoostDb: Int = 0,
) {
    companion object {
        val BOOST_CHOICES_DB = listOf(0, 3, 6, 10)
        const val MAX_BOOST_DB = 12
    }
}

/**
 * How the sleep timer behaves once it is armed.
 *
 * The fade matters more than it sounds: stopping dead wakes people up, which defeats the
 * point. Rewinding on the next play is the other half — whatever was heard in the last
 * minutes before sleep was not really heard.
 */
data class SleepSettings(
    /** Seconds of fade-out before the pause. Zero stops abruptly. */
    val fadeSeconds: Int = 20,
    /** Shake the phone to buy more time without finding the screen in the dark. */
    val shakeToExtend: Boolean = false,
    /** 1 is a deliberate shake, 3 is a nudge. */
    val shakeSensitivity: Int = 2,
    val extendMinutes: Int = 5,
    /** How far back the next play starts, to recover the part that was slept through. */
    val rewindOnWakeSec: Int = 30,
) {
    companion object {
        val FADE_CHOICES = listOf(0, 5, 10, 20, 30, 60)
        val EXTEND_CHOICES = listOf(5, 10, 15, 20, 30)
        val REWIND_CHOICES = listOf(0, 15, 30, 60, 120, 300)
        val SENSITIVITY_CHOICES = listOf(1, 2, 3)
    }
}

/**
 * What happens when the audio route changes.
 *
 * Pausing on disconnect is Android's own `becoming noisy` behaviour and is on by
 * default, because the alternative is a book playing to an empty room. Resuming is the
 * asymmetric part: plugging headphones back in usually means "carry on", while a car
 * connecting usually means the engine started, so the two are separate switches.
 */
data class RouteSettings(
    val pauseOnDisconnect: Boolean = true,
    val resumeOnHeadphones: Boolean = false,
    val resumeInCar: Boolean = true,
)

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
    /**
     * Buttons offered to the notification and lock screen, **in the order they appear**.
     *
     * A list rather than a set, because order is the whole point here. Media3's default
     * notification builds previous / play-pause / next and offers no way in; a custom
     * layout replaces it entirely, at which point the order stops being the framework's
     * business and starts being a setting.
     */
    val notificationButtons: List<TransportButton> = listOf(
        TransportButton.SKIP_BACK,
        TransportButton.SKIP_FORWARD,
    ),
    val headset: HeadsetSettings = HeadsetSettings(),
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
    val audio: AudioSettings = AudioSettings(),
    val sleep: SleepSettings = SleepSettings(),
    val route: RouteSettings = RouteSettings(),
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
