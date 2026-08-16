package io.github.lightheaded.lugu.automation

import io.github.lightheaded.lugu.core.model.SleepMode
import io.github.lightheaded.lugu.core.sync.SpeedSettings

/**
 * One instruction an automation has sent, once it has been checked.
 *
 * Deciding what a caller asked for is kept apart from carrying it out, so every rule
 * about what may be asked lives in one place that runs without an Android device.
 * Anything [AutomationActions.parse] cannot produce is something the playback code never
 * sees, which is what keeps a hostile or merely careless extra from reaching the player.
 */
internal sealed interface AutomationCommand {

    /** Resume. Never pauses: a routine that says play must not stop a book that is playing. */
    data object Play : AutomationCommand

    data object Pause : AutomationCommand

    data object TogglePlayPause : AutomationCommand

    data object NextChapter : AutomationCommand

    data object PreviousChapter : AutomationCommand

    /**
     * A skip in seconds of the book, with the direction carried by the action rather than
     * by the sign of a number — so a negative value is a mistake to be refused rather than
     * an instruction to go the other way.
     *
     * A null [seconds] means the automation did not say how far, which is a request for
     * whatever distance the button in the app moves.
     */
    data class Skip(val forward: Boolean, val seconds: Double?) : AutomationCommand

    data class SetSpeed(val speed: Float) : AutomationCommand

    /** A null [mode] cancels the timer. */
    data class SetSleepTimer(val mode: SleepMode?) : AutomationCommand

    data class PlayFromSearch(val query: String) : AutomationCommand
}

/**
 * The extras of a received broadcast, read for meaning rather than for type.
 *
 * An automation app decides for itself whether "30" leaves as text, as an integer or as a
 * float, and most of them send text. Reading through this interface rather than through
 * the typed getters on a bundle is what stops an action working from `adb` and silently
 * doing nothing from the app it was written for.
 */
internal interface AutomationExtras {

    /** Whether the caller mentioned this key at all, whatever it put in it. */
    fun has(key: String): Boolean

    /** The value as a number, or null when it is absent or is not one. */
    fun number(key: String): Double?

    /** The value as text, or null when it is absent. */
    fun text(key: String): String?

    /** The value as a flag, or null when it is absent or is not one. */
    fun flag(key: String): Boolean?
}

/**
 * The automation surface: action names, extra names, and the rules for both.
 *
 * Every action is namespaced under the application id. Reusing a system action name would
 * mean lugu reacting to broadcasts meant for the platform, and would let anything on the
 * device drive it by accident rather than on purpose.
 */
internal object AutomationActions {

    const val PLAY = "action.PLAY"
    const val PAUSE = "action.PAUSE"
    const val PLAY_PAUSE = "action.PLAY_PAUSE"
    const val SKIP_FORWARD = "action.SKIP_FORWARD"
    const val SKIP_BACK = "action.SKIP_BACK"
    const val NEXT_CHAPTER = "action.NEXT_CHAPTER"
    const val PREVIOUS_CHAPTER = "action.PREVIOUS_CHAPTER"
    const val SET_SPEED = "action.SET_SPEED"
    const val SLEEP_TIMER = "action.SLEEP_TIMER"
    const val SLEEP_CANCEL = "action.SLEEP_CANCEL"
    const val PLAY_SEARCH = "action.PLAY_SEARCH"

    const val EXTRA_SECONDS = "seconds"
    const val EXTRA_SPEED = "speed"
    const val EXTRA_MINUTES = "minutes"
    const val EXTRA_CHAPTERS = "chapters"
    const val EXTRA_END_OF_CHAPTER = "end_of_chapter"
    const val EXTRA_QUERY = "query"

    /** An hour of skipping in one press is already absurd; beyond it is a broken variable. */
    const val MAX_SKIP_SEC = 3_600.0

    /** A day. Longer is not a sleep timer, and the arithmetic stops being meaningful. */
    const val MAX_SLEEP_MINUTES = 1_440

    /** More chapters than any book has, so the count is clamped by the book rather than here. */
    const val MAX_SLEEP_CHAPTERS = 99

    /** Long enough for any spoken request; beyond it the caller is sending something else. */
    const val MAX_QUERY_LENGTH = 200

    /** The full action name a caller must send, for this build. */
    fun actionFor(applicationId: String, name: String): String = "$applicationId.$name"

    /**
     * The action name with the application id taken off, or null when the action is not
     * one of ours.
     *
     * The prefix is the application id rather than a fixed string because the debug build
     * carries an `applicationIdSuffix`. Both builds can then be installed together, and
     * each answers only to its own actions — without this, a routine written against the
     * debug build would quietly drive the release one.
     */
    fun nameOf(action: String?, applicationId: String): String? {
        if (action.isNullOrEmpty()) return null
        val stripped = action.removePrefix("$applicationId.")
        return stripped.takeIf { it != action && it.isNotEmpty() }
    }

    /**
     * What the caller asked for, or null when the answer is "nothing".
     *
     * Null covers an unknown action and an unusable extra alike. Refusing outright is the
     * deliberate choice over guessing: a speed of zero, a negative skip or an empty query
     * are all cases where the closest sensible interpretation is still not what anybody
     * asked for, and playback doing something surprising at three in the morning is worse
     * than playback doing nothing.
     */
    fun parse(
        action: String?,
        applicationId: String,
        extras: AutomationExtras,
    ): AutomationCommand? = when (nameOf(action, applicationId)) {
        PLAY -> AutomationCommand.Play
        PAUSE -> AutomationCommand.Pause
        PLAY_PAUSE -> AutomationCommand.TogglePlayPause
        NEXT_CHAPTER -> AutomationCommand.NextChapter
        PREVIOUS_CHAPTER -> AutomationCommand.PreviousChapter
        SKIP_FORWARD -> skip(forward = true, extras = extras)
        SKIP_BACK -> skip(forward = false, extras = extras)
        SET_SPEED -> speed(extras)
        SLEEP_TIMER -> sleep(extras)
        SLEEP_CANCEL -> AutomationCommand.SetSleepTimer(null)
        PLAY_SEARCH -> search(extras)
        else -> null
    }

    private fun skip(forward: Boolean, extras: AutomationExtras): AutomationCommand? {
        // Nothing said means "as far as the button goes", which the receiver resolves from
        // the listener's own settings.
        if (!extras.has(EXTRA_SECONDS)) return AutomationCommand.Skip(forward, null)
        val seconds = extras.number(EXTRA_SECONDS) ?: return null
        if (!seconds.isFinite() || seconds <= 0.0 || seconds > MAX_SKIP_SEC) return null
        return AutomationCommand.Skip(forward, seconds)
    }

    private fun speed(extras: AutomationExtras): AutomationCommand? {
        val speed = extras.number(EXTRA_SPEED) ?: return null
        // Zero and below are refused rather than clamped: a speed of zero reads as "stop",
        // and silently turning it into the slowest playback would be answering a different
        // question. Anything else is clamped, so a variable that drifted to 4x still plays.
        if (!speed.isFinite() || speed <= 0.0) return null
        return AutomationCommand.SetSpeed(
            speed.toFloat().coerceIn(SpeedSettings.MIN, SpeedSettings.MAX),
        )
    }

    private fun sleep(extras: AutomationExtras): AutomationCommand? {
        val minutes = extras.number(EXTRA_MINUTES)
            ?.takeIf { it.isFinite() && it >= 1.0 && it <= MAX_SLEEP_MINUTES }
            ?.let { SleepMode.Duration(it.toInt()) }
        val chapters = extras.number(EXTRA_CHAPTERS)
            ?.takeIf { it.isFinite() && it >= 1.0 && it <= MAX_SLEEP_CHAPTERS }
            ?.let { SleepMode.Chapters(it.toInt()) }
        val endOfChapter = SleepMode.EndOfChapter.takeIf { extras.flag(EXTRA_END_OF_CHAPTER) == true }

        // Exactly one way of saying when to stop. Two is an ambiguous instruction, and
        // picking one of them by a rule nobody can see is how an automation ends up doing
        // something its author cannot explain.
        val asked = listOfNotNull(minutes, chapters, endOfChapter)
        return asked.singleOrNull()?.let { AutomationCommand.SetSleepTimer(it) }
    }

    private fun search(extras: AutomationExtras): AutomationCommand? {
        val query = extras.text(EXTRA_QUERY)?.trim().orEmpty()
        if (query.isEmpty() || query.length > MAX_QUERY_LENGTH) return null
        return AutomationCommand.PlayFromSearch(query)
    }
}
