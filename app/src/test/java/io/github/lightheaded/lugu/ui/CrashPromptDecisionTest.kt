package io.github.lightheaded.lugu.ui

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.sync.CrashPromptDecision
import org.junit.Test

/**
 * The case worth protecting is the crash loop: an app that falls over on every launch
 * must ask once, not once per launch.
 */
class CrashPromptDecisionTest {

    @Test
    fun `a clean run is not asked about`() {
        val key = CrashPromptDecision.keyToAskAbout(
            crashedLastRun = false,
            lastCrashEventId = null,
            alreadyAskedKey = null,
        )

        assertThat(key).isNull()
    }

    @Test
    fun `a recorded crash is asked about`() {
        val key = CrashPromptDecision.keyToAskAbout(
            crashedLastRun = true,
            lastCrashEventId = "abc",
            alreadyAskedKey = null,
        )

        assertThat(key).isEqualTo("abc")
    }

    @Test
    fun `an id outliving the crash flag is still asked about`() {
        val key = CrashPromptDecision.keyToAskAbout(
            crashedLastRun = false,
            lastCrashEventId = "abc",
            alreadyAskedKey = null,
        )

        assertThat(key).isEqualTo("abc")
    }

    @Test
    fun `the same crash is asked about only once`() {
        val key = CrashPromptDecision.keyToAskAbout(
            crashedLastRun = true,
            lastCrashEventId = "abc",
            alreadyAskedKey = "abc",
        )

        assertThat(key).isNull()
    }

    @Test
    fun `a different crash is asked about even after the first was declined`() {
        val key = CrashPromptDecision.keyToAskAbout(
            crashedLastRun = true,
            lastCrashEventId = "def",
            alreadyAskedKey = "abc",
        )

        assertThat(key).isEqualTo("def")
    }

    @Test
    fun `a crash with no id gets one fixed key so it cannot nag`() {
        val first = CrashPromptDecision.keyToAskAbout(
            crashedLastRun = true,
            lastCrashEventId = null,
            alreadyAskedKey = null,
        )
        val second = CrashPromptDecision.keyToAskAbout(
            crashedLastRun = true,
            lastCrashEventId = null,
            alreadyAskedKey = first,
        )

        assertThat(first).isEqualTo(CrashPromptDecision.UNIDENTIFIED)
        assertThat(second).isNull()
    }
}
