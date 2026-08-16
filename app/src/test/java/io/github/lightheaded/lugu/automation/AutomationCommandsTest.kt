package io.github.lightheaded.lugu.automation

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.SleepMode
import io.github.lightheaded.lugu.core.sync.SpeedSettings
import org.junit.Test

/** Extras as an automation app sends them: a map of whatever types its author picked. */
private class MapExtras(private val values: Map<String, Any?>) : AutomationExtras {
    override fun has(key: String): Boolean = values.containsKey(key)

    override fun number(key: String): Double? = when (val value = values[key]) {
        is Number -> value.toDouble()
        is CharSequence -> value.toString().trim().toDoubleOrNull()
        else -> null
    }

    override fun text(key: String): String? = values[key] as? String

    override fun flag(key: String): Boolean? = when (val value = values[key]) {
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        else -> null
    }
}

private const val APP_ID = "io.github.lightheaded.lugu"

private fun parse(name: String, vararg extras: Pair<String, Any?>) =
    AutomationActions.parse(
        action = AutomationActions.actionFor(APP_ID, name),
        applicationId = APP_ID,
        extras = MapExtras(extras.toMap()),
    )

class AutomationCommandsTest {

    @Test
    fun `the transport verbs need no extras`() {
        assertThat(parse(AutomationActions.PLAY)).isEqualTo(AutomationCommand.Play)
        assertThat(parse(AutomationActions.PAUSE)).isEqualTo(AutomationCommand.Pause)
        assertThat(parse(AutomationActions.PLAY_PAUSE)).isEqualTo(AutomationCommand.TogglePlayPause)
        assertThat(parse(AutomationActions.NEXT_CHAPTER)).isEqualTo(AutomationCommand.NextChapter)
        assertThat(parse(AutomationActions.PREVIOUS_CHAPTER))
            .isEqualTo(AutomationCommand.PreviousChapter)
    }

    @Test
    fun `an action belonging to another build is not ours`() {
        // The debug build's actions reach a release build unchanged, and must do nothing
        // there — otherwise a routine written against one drives the other.
        val debugAction = AutomationActions.actionFor("$APP_ID.debug", AutomationActions.PLAY)
        assertThat(AutomationActions.parse(debugAction, APP_ID, MapExtras(emptyMap()))).isNull()
        assertThat(AutomationActions.parse("android.intent.action.MEDIA_BUTTON", APP_ID, MapExtras(emptyMap())))
            .isNull()
        assertThat(AutomationActions.parse(null, APP_ID, MapExtras(emptyMap()))).isNull()
    }

    @Test
    fun `the debug build answers to its own actions`() {
        val debugId = "$APP_ID.debug"
        val action = AutomationActions.actionFor(debugId, AutomationActions.PAUSE)
        assertThat(AutomationActions.parse(action, debugId, MapExtras(emptyMap())))
            .isEqualTo(AutomationCommand.Pause)
    }

    @Test
    fun `a skip with no distance defers to the listener's own setting`() {
        assertThat(parse(AutomationActions.SKIP_FORWARD))
            .isEqualTo(AutomationCommand.Skip(forward = true, seconds = null))
        assertThat(parse(AutomationActions.SKIP_BACK))
            .isEqualTo(AutomationCommand.Skip(forward = false, seconds = null))
    }

    @Test
    fun `a skip distance is read whatever type it arrived as`() {
        assertThat(parse(AutomationActions.SKIP_FORWARD, "seconds" to 45))
            .isEqualTo(AutomationCommand.Skip(forward = true, seconds = 45.0))
        assertThat(parse(AutomationActions.SKIP_FORWARD, "seconds" to "45"))
            .isEqualTo(AutomationCommand.Skip(forward = true, seconds = 45.0))
        assertThat(parse(AutomationActions.SKIP_BACK, "seconds" to 12.5f))
            .isEqualTo(AutomationCommand.Skip(forward = false, seconds = 12.5))
    }

    @Test
    fun `a nonsensical skip is refused rather than reinterpreted`() {
        // The direction is in the action name, so a negative number is a mistake in the
        // routine rather than an instruction to go the other way.
        assertThat(parse(AutomationActions.SKIP_FORWARD, "seconds" to -30)).isNull()
        assertThat(parse(AutomationActions.SKIP_FORWARD, "seconds" to 0)).isNull()
        assertThat(parse(AutomationActions.SKIP_BACK, "seconds" to "soon")).isNull()
        assertThat(parse(AutomationActions.SKIP_BACK, "seconds" to Double.NaN)).isNull()
        assertThat(
            parse(AutomationActions.SKIP_FORWARD, "seconds" to AutomationActions.MAX_SKIP_SEC + 1),
        ).isNull()
    }

    @Test
    fun `a speed outside the range is clamped, but a speed of zero is refused`() {
        assertThat(parse(AutomationActions.SET_SPEED, "speed" to 1.5))
            .isEqualTo(AutomationCommand.SetSpeed(1.5f))
        assertThat(parse(AutomationActions.SET_SPEED, "speed" to "1.5"))
            .isEqualTo(AutomationCommand.SetSpeed(1.5f))
        assertThat(parse(AutomationActions.SET_SPEED, "speed" to 9))
            .isEqualTo(AutomationCommand.SetSpeed(SpeedSettings.MAX))
        assertThat(parse(AutomationActions.SET_SPEED, "speed" to 0.1))
            .isEqualTo(AutomationCommand.SetSpeed(SpeedSettings.MIN))

        assertThat(parse(AutomationActions.SET_SPEED, "speed" to 0)).isNull()
        assertThat(parse(AutomationActions.SET_SPEED, "speed" to -1)).isNull()
        assertThat(parse(AutomationActions.SET_SPEED, "speed" to Double.POSITIVE_INFINITY)).isNull()
        assertThat(parse(AutomationActions.SET_SPEED)).isNull()
    }

    @Test
    fun `a sleep timer is armed by minutes, by chapters or at the chapter end`() {
        assertThat(parse(AutomationActions.SLEEP_TIMER, "minutes" to 30))
            .isEqualTo(AutomationCommand.SetSleepTimer(SleepMode.Duration(30)))
        assertThat(parse(AutomationActions.SLEEP_TIMER, "chapters" to "2"))
            .isEqualTo(AutomationCommand.SetSleepTimer(SleepMode.Chapters(2)))
        assertThat(parse(AutomationActions.SLEEP_TIMER, "end_of_chapter" to true))
            .isEqualTo(AutomationCommand.SetSleepTimer(SleepMode.EndOfChapter))
    }

    @Test
    fun `two ways of saying when to stop is no instruction at all`() {
        assertThat(parse(AutomationActions.SLEEP_TIMER, "minutes" to 30, "chapters" to 2)).isNull()
        assertThat(
            parse(AutomationActions.SLEEP_TIMER, "minutes" to 30, "end_of_chapter" to true),
        ).isNull()
        // A flag that says no is not a second instruction, so this one is unambiguous.
        assertThat(parse(AutomationActions.SLEEP_TIMER, "minutes" to 30, "end_of_chapter" to false))
            .isEqualTo(AutomationCommand.SetSleepTimer(SleepMode.Duration(30)))
    }

    @Test
    fun `an unusable sleep value arms nothing`() {
        assertThat(parse(AutomationActions.SLEEP_TIMER)).isNull()
        assertThat(parse(AutomationActions.SLEEP_TIMER, "minutes" to 0)).isNull()
        assertThat(parse(AutomationActions.SLEEP_TIMER, "minutes" to -15)).isNull()
        assertThat(parse(AutomationActions.SLEEP_TIMER, "chapters" to 0)).isNull()
        assertThat(
            parse(AutomationActions.SLEEP_TIMER, "chapters" to AutomationActions.MAX_SLEEP_CHAPTERS + 1),
        ).isNull()
        assertThat(
            parse(AutomationActions.SLEEP_TIMER, "minutes" to AutomationActions.MAX_SLEEP_MINUTES + 1),
        ).isNull()
        assertThat(parse(AutomationActions.SLEEP_TIMER, "minutes" to "tonight")).isNull()
    }

    @Test
    fun `cancelling needs nothing to be said about how`() {
        assertThat(parse(AutomationActions.SLEEP_CANCEL))
            .isEqualTo(AutomationCommand.SetSleepTimer(null))
        // Extras that mean nothing here are ignored rather than making the cancel fail.
        assertThat(parse(AutomationActions.SLEEP_CANCEL, "minutes" to 30))
            .isEqualTo(AutomationCommand.SetSleepTimer(null))
    }

    @Test
    fun `a search query is trimmed, and an empty one is not a search`() {
        assertThat(parse(AutomationActions.PLAY_SEARCH, "query" to "  lighthouse wakes  "))
            .isEqualTo(AutomationCommand.PlayFromSearch("lighthouse wakes"))
        assertThat(parse(AutomationActions.PLAY_SEARCH, "query" to "   ")).isNull()
        assertThat(parse(AutomationActions.PLAY_SEARCH, "query" to "")).isNull()
        assertThat(parse(AutomationActions.PLAY_SEARCH)).isNull()
        assertThat(
            parse(AutomationActions.PLAY_SEARCH, "query" to "x".repeat(AutomationActions.MAX_QUERY_LENGTH + 1)),
        ).isNull()
    }
}
