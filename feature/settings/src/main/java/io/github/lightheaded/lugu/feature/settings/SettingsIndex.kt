package io.github.lightheaded.lugu.feature.settings

import androidx.compose.runtime.Composable

/**
 * One searchable setting.
 *
 * [keywords] is the part that makes search useful rather than decorative: people look
 * for "data" and "mobile" when they mean Wi-Fi only, for "2x" when they mean speed, and
 * for "headphones" when they mean the notification buttons. Matching only on the visible
 * title would find none of those, and a search box that fails on the obvious synonym is
 * worse than no search box, because it implies the setting is not there.
 */
internal data class SettingEntry(
    val id: String,
    val category: String,
    val title: String,
    val keywords: String = "",
    val content: @Composable () -> Unit,
)

internal object SettingsIndex {
    /**
     * Every term must match somewhere, so terms narrow rather than widen — typing more
     * of what you remember gets you closer, which is the behaviour a search box is
     * assumed to have.
     */
    fun matches(entry: SettingEntry, query: String): Boolean {
        val terms = query.trim().lowercase().split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return true

        val haystack = buildString {
            append(entry.title.lowercase())
            append(' ')
            append(entry.category.lowercase())
            append(' ')
            append(entry.keywords.lowercase())
        }
        return terms.all { haystack.contains(it) }
    }

    /**
     * The matches, with each category's entries together, in the order the categories
     * first appear.
     *
     * The screen draws a heading only over the first entry of a category, so an entry
     * declared away from the rest of its category would sit under the wrong heading.
     */
    fun filter(entries: List<SettingEntry>, query: String): List<SettingEntry> =
        entries.filter { matches(it, query) }
            .groupBy { it.category }
            .values
            .flatten()

    /** The categories whose entries are not declared together. Empty when the list is sound. */
    fun splitCategories(entries: List<SettingEntry>): List<String> {
        val runs = entries.map { it.category }.fold(mutableListOf<String>()) { acc, category ->
            if (acc.lastOrNull() != category) acc.add(category)
            acc
        }
        return runs.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toList()
    }
}
