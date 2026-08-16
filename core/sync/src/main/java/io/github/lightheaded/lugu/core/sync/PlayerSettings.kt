package io.github.lightheaded.lugu.core.sync

import io.github.lightheaded.lugu.core.model.PodcastTrim

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
     * Lower the book for a short interruption rather than stopping it.
     *
     * On by default, and the right default for spoken word only just: a navigation prompt
     * over a quiet passage is easy to talk over, while a phone call is not — so this
     * applies to *transient* focus loss, which is what the system uses for the former.
     * Anyone who would rather not miss a sentence can turn it off and get a pause.
     */
    val duckOnInterruption: Boolean = true,
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
    /**
     * Whether pausing leaves the timer armed.
     *
     * On by default. A pause is usually an interruption rather than a decision to stay
     * awake, and a timer that silently cancels itself is the complaint upstream has open
     * as app#1317 — the failure is invisible until the book is still playing an hour later.
     */
    val survivesPause: Boolean = true,
) {
    companion object {
        val FADE_CHOICES = listOf(0, 5, 10, 20, 30, 60)
        val EXTEND_CHOICES = listOf(5, 10, 15, 20, 30)
        val REWIND_CHOICES = listOf(0, 15, 30, 60, 120, 300)
        val SENSITIVITY_CHOICES = listOf(1, 2, 3)
    }
}

/**
 * How long the media notification stays, and whether lugu makes one before being asked.
 *
 * The complaint this answers is that a paused book's notification disappears after a couple
 * of minutes, so getting back to it means opening the app and finding it again — which is
 * the single most-repeated grievance about the official app. The opposite complaint is just
 * as real, though: an app that puts itself in the notification shade unbidden, or seizes the
 * headphones the moment they connect, is the behaviour people uninstall Spotify over. So
 * this is a choice with the middle option as the default rather than a switch with an
 * opinion baked in.
 */
enum class NotificationPersistence(val id: String, val label: String) {
    /** Goes as soon as playback stops. The system default, and the quietest. */
    WHILE_PLAYING("playing", "Only while playing"),

    /**
     * Stays after a pause until it is swiped away. What every other media app does, and
     * what makes resuming a press rather than a search.
     */
    UNTIL_DISMISSED("paused", "Until you dismiss it"),

    /**
     * Also loads the last thing played when the app opens, so a headset button works
     * without opening anything first — but **never starts playing on its own**. Arming is
     * not the same as taking over, and the difference is the whole reason this is separate
     * from resuming on a headphone connection, which has its own switch and is off.
     */
    ALWAYS_READY("ready", "Always ready to resume"),
    ;

    val keepsWhilePaused: Boolean get() = this != WHILE_PLAYING

    companion object {
        fun fromId(id: String?): NotificationPersistence =
            entries.firstOrNull { it.id == id } ?: UNTIL_DISMISSED
    }
}

/**
 * What gets trimmed from a podcast episode, and whether the trim announces itself.
 *
 * The per-podcast trim is the real setting; this is only the value a show starts from
 * before anyone has set one, exactly as the default speed is. A listener who subscribes to
 * one advert-heavy network can set the default once and let every other show keep zero.
 *
 * Announcing is on because a silent skip and a lost minute of audio are indistinguishable
 * from the passenger seat, and the app's standing rule is that nothing corrects itself
 * without saying so. The notice carries an undo, which is the part that makes an
 * over-eager trim recoverable rather than merely visible.
 */
data class SkipSettings(
    val defaultTrim: PodcastTrim = PodcastTrim(),
    val announceSkips: Boolean = true,
)

/**
 * What to do about a connection that comes and goes.
 *
 * A phone loses its network constantly — a lift, a tunnel, a cell handover — and a
 * streamed book that stops for a two-second dropout has ended the listening session as
 * surely as a crash. Two settings, because the two halves of the answer cost different
 * things.
 *
 * Buffering ahead costs memory and data. Spoken word is the case where it is nearly free:
 * at the bitrates an audiobook is encoded at, minutes of audio are a couple of megabytes,
 * so lugu can hold a tunnel's worth where a video player could not.
 *
 * Keeping what was streamed costs disk. It buys two things: a re-prepare after a failure
 * replays from disk instead of re-fetching everything the buffer was holding, and audio
 * heard once is still there if the connection is gone the second time. Unlike a download
 * it is disposable — evicted oldest-first when it reaches its bound, and never counted as
 * a download, because deleting a book somebody asked for to make room for one they merely
 * streamed is precisely what makes an offline mode untrustworthy.
 */
data class StreamSettings(
    /** How far ahead to read while streaming. Minutes, because that is how a tunnel is measured. */
    val bufferAheadMinutes: Int = 5,
    /** Disk given to streamed audio, evicted oldest-first. Zero keeps none of it. */
    val retainStreamedMb: Int = 256,
) {
    val retainsStreamed: Boolean get() = retainStreamedMb > 0

    companion object {
        val BUFFER_CHOICES_MIN = listOf(1, 2, 5, 10, 20)
        val RETAIN_CHOICES_MB = listOf(0, 128, 256, 512, 1024, 2048)
        const val MAX_BUFFER_MINUTES = 30
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
    val notification: NotificationPersistence = NotificationPersistence.UNTIL_DISMISSED,
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
    val skip: SkipSettings = SkipSettings(),
    val stream: StreamSettings = StreamSettings(),
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
