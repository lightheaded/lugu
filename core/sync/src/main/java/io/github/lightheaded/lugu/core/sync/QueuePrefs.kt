package io.github.lightheaded.lugu.core.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.queuePrefsStore: DataStore<Preferences> by preferencesDataStore(name = "lugu_queue")

/**
 * What lugu is allowed to start on its own.
 *
 * The distinction the defaults draw is between an instruction and a guess. Something in
 * the queue was put there deliberately, so it plays; a series continuation or the next
 * podcast episode is lugu's own inference, and inferences are announced. Both rules are
 * on by default because silence at the end of a book in a car is worse than a wrong
 * guess you can skip — but [askBeforeSuggestion] exists for anyone who disagrees, and
 * turning either rule off stops it entirely.
 */
data class QueueSettings(
    /** Follow a finished book with the next unstarted volume of its series. */
    val continueSeries: Boolean = true,
    /** Follow a finished episode with the next one published. */
    val continuePodcast: Boolean = true,
    /**
     * Cue a suggestion rather than starting it.
     *
     * Off by default: at the end of a book, hands on a steering wheel, the thing that
     * has to happen is playback continuing. A suggestion that stops and waits for a tap
     * is a suggestion nobody hears.
     */
    val askBeforeSuggestion: Boolean = false,
)

@Singleton
class QueuePrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.queuePrefsStore

    val settings: Flow<QueueSettings> = store.data.map { it.toSettings() }

    suspend fun current(): QueueSettings = settings.first()

    suspend fun setContinueSeries(enabled: Boolean) {
        store.edit { it[CONTINUE_SERIES] = enabled }
    }

    suspend fun setContinuePodcast(enabled: Boolean) {
        store.edit { it[CONTINUE_PODCAST] = enabled }
    }

    suspend fun setAskBeforeSuggestion(enabled: Boolean) {
        store.edit { it[ASK_FIRST] = enabled }
    }

    private fun Preferences.toSettings() = QueueSettings(
        continueSeries = this[CONTINUE_SERIES] ?: DEFAULTS.continueSeries,
        continuePodcast = this[CONTINUE_PODCAST] ?: DEFAULTS.continuePodcast,
        askBeforeSuggestion = this[ASK_FIRST] ?: DEFAULTS.askBeforeSuggestion,
    )

    private companion object {
        val DEFAULTS = QueueSettings()

        val CONTINUE_SERIES = booleanPreferencesKey("continue_series")
        val CONTINUE_PODCAST = booleanPreferencesKey("continue_podcast")
        val ASK_FIRST = booleanPreferencesKey("ask_before_suggestion")
    }
}
