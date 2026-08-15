package io.github.lightheaded.lugu.core.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lightheaded.lugu.core.model.MediaType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.playbackPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "lugu_playback")

/**
 * Playback preferences and player settings, all device-local.
 *
 * Per-item speed is the notable one: Audiobookshelf has no field for it and three open
 * requests asking for one (server #3485, #911, #1980), so a client that wants a
 * narrator's speed to stick has to remember it itself.
 *
 * These live in DataStore rather than Room deliberately. They are not synced data and
 * nothing joins against them, so putting them in the database would buy nothing and
 * cost a schema migration on every installed device.
 *
 * Every mutator returns Unit rather than DataStore's `Preferences`, so feature modules
 * can change a setting without taking a dependency on DataStore.
 */
@Singleton
class PlaybackPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.playbackPrefsStore

    val settings: Flow<PlayerSettings> = store.data.map { it.toPlayerSettings() }

    suspend fun currentSettings(): PlayerSettings = settings.first()

    suspend fun setSkipBack(seconds: Int) {
        store.edit { it[SKIP_BACK] = seconds.coerceIn(1, 300) }
    }

    suspend fun setSkipForward(seconds: Int) {
        store.edit { it[SKIP_FORWARD] = seconds.coerceIn(1, 300) }
    }

    suspend fun setNoticeSeconds(seconds: Int) {
        store.edit { it[NOTICE_SECONDS] = seconds.coerceIn(2, 120) }
    }

    suspend fun setPlayerButtons(buttons: Set<TransportButton>) {
        store.edit { prefs -> prefs[PLAYER_BUTTONS] = buttons.joinToString(",") { it.id } }
    }

    suspend fun setNotificationButtons(buttons: Set<TransportButton>) {
        store.edit { prefs -> prefs[NOTIFICATION_BUTTONS] = buttons.joinToString(",") { it.id } }
    }

    suspend fun setDefaultSpeed(speed: Float) {
        store.edit { it[DEFAULT_SPEED] = speed.coerceIn(SpeedSettings.MIN, SpeedSettings.MAX) }
    }

    suspend fun setDefaultPodcastSpeed(speed: Float) {
        store.edit { it[DEFAULT_PODCAST_SPEED] = speed.coerceIn(SpeedSettings.MIN, SpeedSettings.MAX) }
    }

    suspend fun setSeparatePodcastSpeed(enabled: Boolean) {
        store.edit { it[SEPARATE_PODCAST_SPEED] = enabled }
    }

    suspend fun setRememberPerBook(enabled: Boolean) {
        store.edit { it[REMEMBER_PER_BOOK] = enabled }
    }

    suspend fun setRememberPerPodcast(enabled: Boolean) {
        store.edit { it[REMEMBER_PER_PODCAST] = enabled }
    }

    suspend fun setSkipSilence(enabled: Boolean) {
        store.edit { it[SKIP_SILENCE] = enabled }
    }

    suspend fun setVolumeBoostDb(db: Int) {
        store.edit { it[VOLUME_BOOST_DB] = db.coerceIn(0, AudioSettings.MAX_BOOST_DB) }
    }

    suspend fun setSleepFadeSeconds(seconds: Int) {
        store.edit { it[SLEEP_FADE] = seconds.coerceIn(0, 300) }
    }

    suspend fun setShakeToExtend(enabled: Boolean) {
        store.edit { it[SLEEP_SHAKE] = enabled }
    }

    suspend fun setShakeSensitivity(level: Int) {
        store.edit { it[SLEEP_SHAKE_SENSITIVITY] = level.coerceIn(1, 3) }
    }

    suspend fun setSleepExtendMinutes(minutes: Int) {
        store.edit { it[SLEEP_EXTEND] = minutes.coerceIn(1, 120) }
    }

    suspend fun setRewindOnWakeSec(seconds: Int) {
        store.edit { it[SLEEP_REWIND] = seconds.coerceIn(0, 600) }
    }

    suspend fun setPauseOnDisconnect(enabled: Boolean) {
        store.edit { it[PAUSE_ON_DISCONNECT] = enabled }
    }

    suspend fun setResumeOnHeadphones(enabled: Boolean) {
        store.edit { it[RESUME_HEADPHONES] = enabled }
    }

    suspend fun setResumeInCar(enabled: Boolean) {
        store.edit { it[RESUME_CAR] = enabled }
    }

    suspend fun setSpeedPresets(presets: List<Float>) {
        store.edit { prefs ->
            prefs[SPEED_PRESETS] = presets
                .map { it.coerceIn(SpeedSettings.MIN, SpeedSettings.MAX) }
                .distinct()
                .sorted()
                .joinToString(",")
        }
    }

    /**
     * The speed this item should start at: its own remembered speed if remembering is
     * on for its kind, else the type default, else the global default.
     */
    suspend fun speedFor(itemId: String, mediaType: MediaType): Float {
        val prefs = store.data.first()
        val speed = prefs.toPlayerSettings().speed
        val isPodcast = mediaType == MediaType.PODCAST

        val remembers = if (isPodcast) speed.rememberPerPodcast else speed.rememberPerBook
        if (remembers) prefs[speedKey(itemId)]?.let { return it }

        return if (isPodcast && speed.separatePodcastSpeed) speed.defaultPodcastSpeed else speed.defaultSpeed
    }

    /**
     * Remembers the speed for one item. Setting it back to the applicable default
     * forgets the override, so the item follows the default again rather than pinning
     * itself to a value that then stops tracking preference changes.
     */
    suspend fun setSpeedFor(itemId: String, mediaType: MediaType, speed: Float) {
        val clamped = speed.coerceIn(SpeedSettings.MIN, SpeedSettings.MAX)
        val current = currentSettings().speed
        val isPodcast = mediaType == MediaType.PODCAST
        if (!(if (isPodcast) current.rememberPerPodcast else current.rememberPerBook)) return

        val default = if (isPodcast && current.separatePodcastSpeed) {
            current.defaultPodcastSpeed
        } else {
            current.defaultSpeed
        }
        store.edit { prefs ->
            if (kotlin.math.abs(clamped - default) < 0.001f) {
                prefs.remove(speedKey(itemId))
            } else {
                prefs[speedKey(itemId)] = clamped
            }
        }
    }

    private fun Preferences.toPlayerSettings(): PlayerSettings = PlayerSettings(
        skipBackSec = this[SKIP_BACK] ?: DEFAULTS.skipBackSec,
        skipForwardSec = this[SKIP_FORWARD] ?: DEFAULTS.skipForwardSec,
        playerButtons = this[PLAYER_BUTTONS]?.toButtons() ?: DEFAULTS.playerButtons,
        notificationButtons = this[NOTIFICATION_BUTTONS]?.toButtons() ?: DEFAULTS.notificationButtons,
        noticeSeconds = this[NOTICE_SECONDS] ?: DEFAULTS.noticeSeconds,
        speed = SpeedSettings(
            defaultSpeed = this[DEFAULT_SPEED] ?: 1.0f,
            separatePodcastSpeed = this[SEPARATE_PODCAST_SPEED] ?: false,
            defaultPodcastSpeed = this[DEFAULT_PODCAST_SPEED] ?: 1.2f,
            rememberPerBook = this[REMEMBER_PER_BOOK] ?: true,
            rememberPerPodcast = this[REMEMBER_PER_PODCAST] ?: true,
            presets = this[SPEED_PRESETS]?.toSpeedList() ?: SpeedSettings.DEFAULT_PRESETS,
        ),
        audio = AudioSettings(
            skipSilence = this[SKIP_SILENCE] ?: DEFAULTS.audio.skipSilence,
            volumeBoostDb = this[VOLUME_BOOST_DB] ?: DEFAULTS.audio.volumeBoostDb,
        ),
        sleep = SleepSettings(
            fadeSeconds = this[SLEEP_FADE] ?: DEFAULTS.sleep.fadeSeconds,
            shakeToExtend = this[SLEEP_SHAKE] ?: DEFAULTS.sleep.shakeToExtend,
            shakeSensitivity = this[SLEEP_SHAKE_SENSITIVITY] ?: DEFAULTS.sleep.shakeSensitivity,
            extendMinutes = this[SLEEP_EXTEND] ?: DEFAULTS.sleep.extendMinutes,
            rewindOnWakeSec = this[SLEEP_REWIND] ?: DEFAULTS.sleep.rewindOnWakeSec,
        ),
        route = RouteSettings(
            pauseOnDisconnect = this[PAUSE_ON_DISCONNECT] ?: DEFAULTS.route.pauseOnDisconnect,
            resumeOnHeadphones = this[RESUME_HEADPHONES] ?: DEFAULTS.route.resumeOnHeadphones,
            resumeInCar = this[RESUME_CAR] ?: DEFAULTS.route.resumeInCar,
        ),
    )

    private fun String.toButtons(): Set<TransportButton> =
        split(',').mapNotNull { TransportButton.fromId(it.trim()) }.toSet()

    private fun String.toSpeedList(): List<Float> =
        split(',').mapNotNull { it.trim().toFloatOrNull() }
            .filter { it in SpeedSettings.MIN..SpeedSettings.MAX }
            .distinct()
            .sorted()
            .ifEmpty { SpeedSettings.DEFAULT_PRESETS }

    /**
     * Speed is keyed on the library item. For a podcast that is the *podcast*, not the
     * episode — a listener picks a speed for a narrator, and the narrator does not
     * change between episodes.
     */
    private fun speedKey(itemId: String) = floatPreferencesKey("speed_$itemId")

    private companion object {
        val DEFAULTS = PlayerSettings()

        val SKIP_BACK = intPreferencesKey("skip_back_sec")
        val SKIP_FORWARD = intPreferencesKey("skip_forward_sec")
        val NOTICE_SECONDS = intPreferencesKey("notice_seconds")
        val PLAYER_BUTTONS = stringPreferencesKey("player_buttons")
        val NOTIFICATION_BUTTONS = stringPreferencesKey("notification_buttons")
        val DEFAULT_SPEED = floatPreferencesKey("default_speed")
        val DEFAULT_PODCAST_SPEED = floatPreferencesKey("default_podcast_speed")
        val SEPARATE_PODCAST_SPEED = booleanPreferencesKey("separate_podcast_speed")
        val REMEMBER_PER_BOOK = booleanPreferencesKey("remember_per_book")
        val REMEMBER_PER_PODCAST = booleanPreferencesKey("remember_per_podcast")
        val SPEED_PRESETS = stringPreferencesKey("speed_presets")
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val VOLUME_BOOST_DB = intPreferencesKey("volume_boost_db")
        val SLEEP_FADE = intPreferencesKey("sleep_fade_seconds")
        val SLEEP_SHAKE = booleanPreferencesKey("sleep_shake_to_extend")
        val SLEEP_SHAKE_SENSITIVITY = intPreferencesKey("sleep_shake_sensitivity")
        val SLEEP_EXTEND = intPreferencesKey("sleep_extend_minutes")
        val SLEEP_REWIND = intPreferencesKey("sleep_rewind_on_wake_sec")
        val PAUSE_ON_DISCONNECT = booleanPreferencesKey("pause_on_disconnect")
        val RESUME_HEADPHONES = booleanPreferencesKey("resume_on_headphones")
        val RESUME_CAR = booleanPreferencesKey("resume_in_car")
    }
}
