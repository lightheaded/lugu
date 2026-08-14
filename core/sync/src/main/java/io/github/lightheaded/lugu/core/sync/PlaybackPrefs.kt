package io.github.lightheaded.lugu.core.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.playbackPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "lugu_playback")

/**
 * Playback preferences that only exist on this device.
 *
 * Per-book speed is the notable one: Audiobookshelf has no field for it and three open
 * requests asking for one (server #3485, #911, #1980), so a client that wants a
 * narrator-specific speed to stick has to remember it itself.
 *
 * These live in DataStore rather than Room deliberately. They are not synced data and
 * nothing joins against them, so putting them in the database would buy nothing and
 * cost a schema migration on every installed device.
 */
@Singleton
class PlaybackPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.playbackPrefsStore

    private fun speedKey(itemId: String) = floatPreferencesKey("speed_$itemId")

    fun observeDefaultSpeed(): Flow<Float> =
        store.data.map { it[DEFAULT_SPEED] ?: NORMAL_SPEED }

    suspend fun defaultSpeed(): Float = observeDefaultSpeed().first()

    suspend fun setDefaultSpeed(speed: Float) {
        store.edit { it[DEFAULT_SPEED] = speed.coerceIn(MIN_SPEED, MAX_SPEED) }
    }

    /** The speed to start this book at: its own remembered speed, else the global default. */
    suspend fun speedFor(itemId: String): Float {
        val prefs = store.data.first()
        return prefs[speedKey(itemId)] ?: prefs[DEFAULT_SPEED] ?: NORMAL_SPEED
    }

    /**
     * Remembers the speed for one book. Setting it back to 1× forgets the override so
     * the book follows the global default again rather than pinning itself to normal.
     */
    suspend fun setSpeedFor(itemId: String, speed: Float) {
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        store.edit { prefs ->
            if (clamped == NORMAL_SPEED) prefs.remove(speedKey(itemId)) else prefs[speedKey(itemId)] = clamped
        }
    }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.5f
        const val NORMAL_SPEED = 1.0f
        const val SPEED_STEP = 0.05f

        private val DEFAULT_SPEED = floatPreferencesKey("default_speed")

        /** The speeds offered as one-tap chips. */
        val PRESETS = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f)
    }
}
