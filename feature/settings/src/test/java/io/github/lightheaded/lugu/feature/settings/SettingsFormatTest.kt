package io.github.lightheaded.lugu.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsFormatTest {

    @Test
    fun `seconds carry a space before the unit`() {
        assertThat(formatSeconds(5)).isEqualTo("5 s")
        assertThat(formatSeconds(30)).isEqualTo("30 s")
    }

    @Test
    fun `whole minutes read as minutes`() {
        assertThat(formatSeconds(60)).isEqualTo("1 min")
        assertThat(formatSeconds(120)).isEqualTo("2 min")
        assertThat(formatSeconds(300)).isEqualTo("5 min")
    }

    /** "1.5 min" would be a figure to work out; ninety seconds is not. */
    @Test
    fun `minutes that do not divide evenly stay in seconds`() {
        assertThat(formatSeconds(45)).isEqualTo("45 s")
        assertThat(formatSeconds(90)).isEqualTo("90 s")
    }
}
