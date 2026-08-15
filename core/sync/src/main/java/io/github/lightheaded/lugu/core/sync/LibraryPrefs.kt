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
data class LibrarySettings(
    val hiddenMediaTypes: Set<MediaType> = emptySet(),
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
) {
    fun isVisible(mediaType: MediaType): Boolean = mediaType !in hiddenMediaTypes
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

    private fun Preferences.toSettings(): LibrarySettings = LibrarySettings(
        hiddenMediaTypes = this[HIDDEN_TYPES]?.toMediaTypes() ?: emptySet(),
        selectedLibraryId = this[SELECTED_LIBRARY],
        shelvesFollowLibrary = this[SHELVES_FOLLOW_LIBRARY] ?: true,
        itemSort = ItemSort.fromId(this[ITEM_SORT]),
        itemFilter = ListFilter.fromId(this[ITEM_FILTER]),
        episodeSort = EpisodeSort.fromId(this[EPISODE_SORT]),
        episodeFilter = ListFilter.fromId(this[EPISODE_FILTER]),
    )

    private fun String.toMediaTypes(): Set<MediaType> =
        split(',').mapNotNull { name -> MediaType.entries.firstOrNull { it.name == name.trim() } }.toSet()

    private companion object {
        val HIDDEN_TYPES = stringPreferencesKey("hidden_media_types")
        val SELECTED_LIBRARY = stringPreferencesKey("selected_library_id")
        val SHELVES_FOLLOW_LIBRARY = booleanPreferencesKey("shelves_follow_library")
        val ITEM_SORT = stringPreferencesKey("item_sort")
        val ITEM_FILTER = stringPreferencesKey("item_filter")
        val EPISODE_SORT = stringPreferencesKey("episode_sort")
        val EPISODE_FILTER = stringPreferencesKey("episode_filter")
    }
}
