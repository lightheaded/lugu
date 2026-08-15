package io.github.lightheaded.lugu.feature.settings

import androidx.compose.runtime.Composable
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsIndexTest {

    private val noContent: @Composable () -> Unit = {}

    private fun entry(id: String, category: String, title: String, keywords: String = "") =
        SettingEntry(id, category, title, keywords, noContent)

    private val entries = listOf(
        entry("skip-back", "Skipping", "Skip back", "rewind back seconds replay missed"),
        entry("download-wifi", "Downloads", "Download on Wi-Fi only", "wifi mobile data cellular metered"),
        entry("speed-default", "Speed", "Default speed", "tempo rate faster slower 1.5x 2x"),
    )

    @Test
    fun `an empty query shows everything`() {
        assertThat(SettingsIndex.filter(entries, "")).hasSize(3)
        assertThat(SettingsIndex.filter(entries, "   ")).hasSize(3)
    }

    @Test
    fun `matches on the visible title`() {
        assertThat(SettingsIndex.filter(entries, "skip").map { it.id }).containsExactly("skip-back")
    }

    /**
     * The reason keywords exist. Someone looking for the Wi-Fi setting searches "data" or
     * "mobile"; neither word appears in the title, and a search that returned nothing
     * would imply lugu has no such setting.
     */
    @Test
    fun `matches on the word someone would actually type`() {
        assertThat(SettingsIndex.filter(entries, "mobile data").map { it.id })
            .containsExactly("download-wifi")
        assertThat(SettingsIndex.filter(entries, "cellular").map { it.id })
            .containsExactly("download-wifi")
        assertThat(SettingsIndex.filter(entries, "rewind").map { it.id })
            .containsExactly("skip-back")
        assertThat(SettingsIndex.filter(entries, "2x").map { it.id })
            .containsExactly("speed-default")
    }

    @Test
    fun `matches on the category name`() {
        assertThat(SettingsIndex.filter(entries, "downloads").map { it.id })
            .containsExactly("download-wifi")
    }

    @Test
    fun `every term must match, so typing more narrows`() {
        // "speed" alone is ambiguous; adding a term that only one entry has narrows it.
        assertThat(SettingsIndex.filter(entries, "speed")).hasSize(1)
        assertThat(SettingsIndex.filter(entries, "speed tempo")).hasSize(1)
        assertThat(SettingsIndex.filter(entries, "speed wifi")).isEmpty()
    }

    @Test
    fun `search ignores case`() {
        assertThat(SettingsIndex.filter(entries, "WI-FI").map { it.id }).containsExactly("download-wifi")
        assertThat(SettingsIndex.filter(entries, "SkIp").map { it.id }).containsExactly("skip-back")
    }

    @Test
    fun `a query matching nothing returns nothing rather than everything`() {
        assertThat(SettingsIndex.filter(entries, "chromecast")).isEmpty()
    }
}
