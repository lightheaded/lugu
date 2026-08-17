package io.github.lightheaded.lugu.core.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lightheaded.lugu.core.model.EpisodeSort
import io.github.lightheaded.lugu.core.model.ItemSort
import io.github.lightheaded.lugu.core.model.ListFilter
import io.github.lightheaded.lugu.core.model.MediaType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.libraryPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "lugu_library")

/**
 * How the library is shaped for this listener.
 *
 * [hiddenMediaTypes] is the honest answer to "why am I seeing this": someone who keeps no
 * podcasts, or keeps them and does not want them here, should not have to explain the
 * tab away — it should not exist. Hiding is by media *type* rather than by library,
 * because that is how people describe it, and because a server can hold several libraries
 * of the same kind.
 *
 * The sort and filter are remembered rather than reset on every visit. A list someone has
 * ordered by length is a decision, and making them take it again on each return is the
 * sort of small friction that adds up to not using the feature.
 */
/** Which of the two tabs opens when lugu does. */
enum class StartTab(val id: String, val label: String) {
    HOME("home", "Home"),
    LIBRARY("library", "Library"),
    ;

    companion object {
        fun fromId(id: String?): StartTab = entries.firstOrNull { it.id == id } ?: HOME
    }
}

data class LibrarySettings(
    val hiddenMediaTypes: Set<MediaType> = emptySet(),
    val startTab: StartTab = StartTab.HOME,
    /**
     * The order shelves appear in, and which of them appear at all.
     *
     * Stored as a list of names rather than as positions, so a shelf added in a later
     * version simply is not in the list and falls back to its declared position instead of
     * silently taking someone else's. Anything absent from [shelfOrder] keeps the order it
     * is declared in; anything in [hiddenShelves] is not shown at all.
     *
     * Worth the storage because the alternative is scrolling past five rows to reach the
     * one you always want, every session (upstream app#743).
     */
    val shelfOrder: List<String> = emptyList(),
    val hiddenShelves: Set<String> = emptySet(),
    /**
     * The library in view.
     *
     * Kept here rather than in a view model because two screens depend on it — the browse
     * grid it filters, and the shelves it scopes. Holding it in one of them and pushing it
     * to the other leaves a frame where the two disagree, which is visible as shelves that
     * briefly span every library before settling.
     */
    val selectedLibraryId: String? = null,
    /**
     * Whether the computed shelves follow the library picker.
     *
     * On by default, because a picker that visibly filters half a screen and silently
     * ignores the other half reads as broken. Off is a real preference — "continue
     * listening" arguably spans everything someone is part-way through — so it is a
     * switch rather than a hardcoded answer.
     */
    val shelvesFollowLibrary: Boolean = true,
    val itemSort: ItemSort = ItemSort.TITLE,
    val itemFilter: ListFilter = ListFilter.ALL,
    val episodeSort: EpisodeSort = EpisodeSort.NEWEST,
    val episodeFilter: ListFilter = ListFilter.ALL,
    /**
     * The downloads screen's own ordering, kept apart from the grid's.
     *
     * Its own keys rather than a share of [itemSort] and [itemFilter], because that screen
     * sorts over bytes and download times that the library's ordering knows nothing about
     * — and because borrowing them would mean re-ordering the grid by visiting Downloads.
     */
    val downloadSort: ItemSort = ItemSort.ADDED,
    val downloadFilter: ListFilter = ListFilter.ALL,
) {
    fun isVisible(mediaType: MediaType): Boolean = mediaType !in hiddenMediaTypes

    /**
     * Applies the stored order and the hidden set to shelves as declared.
     *
     * Declared order is the fallback for anything the stored list has never heard of, so a
     * shelf added in a later version appears where its author put it rather than at the
     * end or, worse, in a position someone else's preference chose for it.
     */
    fun <T> arrangeShelves(declared: List<T>, nameOf: (T) -> String): List<T> {
        val visible = declared.filter { nameOf(it) !in hiddenShelves }
        if (shelfOrder.isEmpty()) return visible
        val known = shelfOrder.withIndex().associate { (index, name) -> name to index }
        return visible.sortedBy { known[nameOf(it)] ?: (shelfOrder.size + declared.indexOf(it)) }
    }
}

@Singleton
class LibraryPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.libraryPrefsStore

    val settings: Flow<LibrarySettings> = store.data.map { it.toSettings() }

    suspend fun current(): LibrarySettings = settings.first()

    /**
     * Hiding every type would leave an app with nothing in it, which is not a preference
     * anyone means — so the last visible type stays visible.
     */
    suspend fun setMediaTypeHidden(mediaType: MediaType, hidden: Boolean) {
        val wanted = if (hidden) {
            (current().hiddenMediaTypes + mediaType).takeIf { it.size < MediaType.entries.size }
                ?: return
        } else {
            current().hiddenMediaTypes - mediaType
        }
        store.edit { prefs -> prefs[HIDDEN_TYPES] = wanted.joinToString(",") { it.name } }
    }

    suspend fun setStartTab(tab: StartTab) {
        store.edit { it[START_TAB] = tab.id }
    }

    suspend fun setShelfOrder(order: List<String>) {
        store.edit { prefs -> prefs[SHELF_ORDER] = order.distinct().joinToString(",") }
    }

    suspend fun setShelfHidden(name: String, hidden: Boolean) {
        val wanted = if (hidden) current().hiddenShelves + name else current().hiddenShelves - name
        store.edit { prefs -> prefs[HIDDEN_SHELVES] = wanted.joinToString(",") }
    }

    suspend fun setSelectedLibraryId(libraryId: String?) {
        store.edit { prefs ->
            if (libraryId == null) prefs.remove(SELECTED_LIBRARY) else prefs[SELECTED_LIBRARY] = libraryId
        }
    }

    suspend fun setShelvesFollowLibrary(enabled: Boolean) {
        store.edit { it[SHELVES_FOLLOW_LIBRARY] = enabled }
    }

    suspend fun setItemSort(sort: ItemSort) {
        store.edit { it[ITEM_SORT] = sort.id }
    }

    suspend fun setItemFilter(filter: ListFilter) {
        store.edit { it[ITEM_FILTER] = filter.id }
    }

    suspend fun setEpisodeSort(sort: EpisodeSort) {
        store.edit { it[EPISODE_SORT] = sort.id }
    }

    suspend fun setEpisodeFilter(filter: ListFilter) {
        store.edit { it[EPISODE_FILTER] = filter.id }
    }

    suspend fun setDownloadSort(sort: ItemSort) {
        store.edit { it[DOWNLOAD_SORT] = sort.id }
    }

    suspend fun setDownloadFilter(filter: ListFilter) {
        store.edit { it[DOWNLOAD_FILTER] = filter.id }
    }

    private fun Preferences.toSettings(): LibrarySettings = LibrarySettings(
        hiddenMediaTypes = this[HIDDEN_TYPES]?.toMediaTypes() ?: emptySet(),
        selectedLibraryId = this[SELECTED_LIBRARY],
        startTab = StartTab.fromId(this[START_TAB]),
        shelfOrder = this[SHELF_ORDER]?.toNames().orEmpty(),
        hiddenShelves = this[HIDDEN_SHELVES]?.toNames()?.toSet().orEmpty(),
        shelvesFollowLibrary = this[SHELVES_FOLLOW_LIBRARY] ?: true,
        itemSort = ItemSort.fromId(this[ITEM_SORT]),
        itemFilter = ListFilter.fromId(this[ITEM_FILTER]),
        episodeSort = EpisodeSort.fromId(this[EPISODE_SORT]),
        episodeFilter = ListFilter.fromId(this[EPISODE_FILTER]),
        // Nothing stored means the declared default rather than [ItemSort.fromId]'s, which
        // is the library grid's answer and not this screen's.
        downloadSort = this[DOWNLOAD_SORT]?.let { ItemSort.fromId(it) } ?: ItemSort.ADDED,
        downloadFilter = ListFilter.fromId(this[DOWNLOAD_FILTER]),
    )

    private fun String.toNames(): List<String> =
        split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun String.toMediaTypes(): Set<MediaType> =
        split(',').mapNotNull { name -> MediaType.entries.firstOrNull { it.name == name.trim() } }.toSet()

    private companion object {
        val HIDDEN_TYPES = stringPreferencesKey("hidden_media_types")
        val SELECTED_LIBRARY = stringPreferencesKey("selected_library_id")
        val START_TAB = stringPreferencesKey("start_tab")
        val SHELF_ORDER = stringPreferencesKey("shelf_order")
        val HIDDEN_SHELVES = stringPreferencesKey("hidden_shelves")
        val SHELVES_FOLLOW_LIBRARY = booleanPreferencesKey("shelves_follow_library")
        val ITEM_SORT = stringPreferencesKey("item_sort")
        val ITEM_FILTER = stringPreferencesKey("item_filter")
        val EPISODE_SORT = stringPreferencesKey("episode_sort")
        val EPISODE_FILTER = stringPreferencesKey("episode_filter")
        val DOWNLOAD_SORT = stringPreferencesKey("download_sort")
        val DOWNLOAD_FILTER = stringPreferencesKey("download_filter")
    }
}
