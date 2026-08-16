package io.github.lightheaded.lugu.core.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lightheaded.lugu.core.model.AutoPlay
import io.github.lightheaded.lugu.core.model.AutoPlayDevice
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.model.PodcastTrim
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

    /** Order is preserved: it is what decides where each button sits in the notification. */
    suspend fun setNotificationButtons(buttons: List<TransportButton>) {
        store.edit { prefs -> prefs[NOTIFICATION_BUTTONS] = buttons.distinct().joinToString(",") { it.id } }
    }

    suspend fun setNotificationPersistence(value: NotificationPersistence) {
        store.edit { it[NOTIFICATION_PERSISTENCE] = value.id }
    }

    suspend fun setHeadsetNextAction(action: HeadsetAction) {
        store.edit { it[HEADSET_NEXT] = action.id }
    }

    suspend fun setHeadsetPreviousAction(action: HeadsetAction) {
        store.edit { it[HEADSET_PREVIOUS] = action.id }
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

    suspend fun setDuckOnInterruption(enabled: Boolean) {
        store.edit { it[DUCK_ON_INTERRUPTION] = enabled }
    }

    suspend fun setSleepSurvivesPause(enabled: Boolean) {
        store.edit { it[SLEEP_SURVIVES_PAUSE] = enabled }
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

    suspend fun setAutoPlayEnabled(enabled: Boolean) {
        store.edit { it[AUTO_PLAY_ENABLED] = enabled }
    }

    suspend fun setAutoPlayWaitSec(seconds: Int) {
        store.edit { it[AUTO_PLAY_WAIT] = seconds.coerceIn(0, AutoPlay.MAX_WAIT_SEC) }
    }

    /**
     * Adds a device, or renames one already there.
     *
     * Keyed on the device rather than appended, so associating the same headphones twice —
     * which the system picker allows, and which happens after a device is forgotten and
     * chosen again — leaves one row rather than two that cannot be told apart.
     */
    suspend fun addAutoPlayDevice(device: AutoPlayDevice) {
        store.edit { prefs ->
            val kept = prefs[AUTO_PLAY_DEVICES].orEmpty()
                .mapNotNull(AutoPlay::decode)
                .filterNot { it.key == device.key }
            prefs[AUTO_PLAY_DEVICES] = (kept + device).map(AutoPlay::encode).toSet()
        }
    }

    suspend fun removeAutoPlayDevice(key: String) {
        store.edit { prefs ->
            prefs[AUTO_PLAY_DEVICES] = prefs[AUTO_PLAY_DEVICES].orEmpty()
                .mapNotNull(AutoPlay::decode)
                .filterNot { it.key == key }
                .map(AutoPlay::encode)
                .toSet()
        }
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

    suspend fun setDefaultTrim(trim: PodcastTrim) {
        store.edit { prefs ->
            prefs[TRIM_INTRO] = trim.introSec.coerceIn(0, PodcastTrim.MAX_TRIM_SEC)
            prefs[TRIM_OUTRO] = trim.outroSec.coerceIn(0, PodcastTrim.MAX_TRIM_SEC)
            prefs[TRIM_ADVERTS] = trim.skipMarkedAdverts
        }
    }

    suspend fun setAnnounceSkips(enabled: Boolean) {
        store.edit { it[ANNOUNCE_SKIPS] = enabled }
    }

    suspend fun setBufferAheadMinutes(minutes: Int) {
        store.edit { it[BUFFER_AHEAD] = minutes.coerceIn(1, StreamSettings.MAX_BUFFER_MINUTES) }
    }

    suspend fun setRetainStreamedMb(megabytes: Int) {
        store.edit { it[RETAIN_STREAMED] = megabytes.coerceAtLeast(0) }
    }

    /**
     * What this podcast trims, falling back to the default for a show nobody has set one
     * for. Keyed on the podcast rather than the episode, because the sting belongs to the
     * show and setting it per episode would mean setting it again every week.
     */
    suspend fun trimFor(podcastId: String): PodcastTrim {
        val prefs = store.data.first()
        if (prefs[trimSetKey(podcastId)] != true) return prefs.toPlayerSettings().skip.defaultTrim
        return PodcastTrim(
            introSec = prefs[trimIntroKey(podcastId)] ?: 0,
            outroSec = prefs[trimOutroKey(podcastId)] ?: 0,
            skipMarkedAdverts = prefs[trimAdvertsKey(podcastId)] ?: false,
        )
    }

    fun observeTrimFor(podcastId: String): Flow<PodcastTrim> = store.data.map { prefs ->
        if (prefs[trimSetKey(podcastId)] != true) {
            prefs.toPlayerSettings().skip.defaultTrim
        } else {
            PodcastTrim(
                introSec = prefs[trimIntroKey(podcastId)] ?: 0,
                outroSec = prefs[trimOutroKey(podcastId)] ?: 0,
                skipMarkedAdverts = prefs[trimAdvertsKey(podcastId)] ?: false,
            )
        }
    }

    /**
     * Records this podcast's own trim. The "set" marker is stored separately from the
     * values so that a show explicitly trimmed to zero stays at zero — without it, turning
     * a trim off would read as "never set" and hand the show the default straight back.
     */
    suspend fun setTrimFor(podcastId: String, trim: PodcastTrim) {
        store.edit { prefs ->
            prefs[trimSetKey(podcastId)] = true
            prefs[trimIntroKey(podcastId)] = trim.introSec.coerceIn(0, PodcastTrim.MAX_TRIM_SEC)
            prefs[trimOutroKey(podcastId)] = trim.outroSec.coerceIn(0, PodcastTrim.MAX_TRIM_SEC)
            prefs[trimAdvertsKey(podcastId)] = trim.skipMarkedAdverts
        }
    }

    /** Puts a show back on the default, as distinct from setting it to nothing. */
    suspend fun clearTrimFor(podcastId: String) {
        store.edit { prefs ->
            prefs.remove(trimSetKey(podcastId))
            prefs.remove(trimIntroKey(podcastId))
            prefs.remove(trimOutroKey(podcastId))
            prefs.remove(trimAdvertsKey(podcastId))
        }
    }

    /** True when this show has a trim of its own rather than following the default. */
    suspend fun hasOwnTrim(podcastId: String): Boolean =
        store.data.first()[trimSetKey(podcastId)] == true

    private fun Preferences.toPlayerSettings(): PlayerSettings = PlayerSettings(
        skipBackSec = this[SKIP_BACK] ?: DEFAULTS.skipBackSec,
        skipForwardSec = this[SKIP_FORWARD] ?: DEFAULTS.skipForwardSec,
        playerButtons = this[PLAYER_BUTTONS]?.toButtons()?.toSet() ?: DEFAULTS.playerButtons,
        notificationButtons = this[NOTIFICATION_BUTTONS]?.toButtons() ?: DEFAULTS.notificationButtons,
        notification = NotificationPersistence.fromId(this[NOTIFICATION_PERSISTENCE]),
        headset = HeadsetSettings(
            nextAction = HeadsetAction.fromId(this[HEADSET_NEXT]) ?: DEFAULTS.headset.nextAction,
            previousAction = HeadsetAction.fromId(this[HEADSET_PREVIOUS]) ?: DEFAULTS.headset.previousAction,
        ),
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
            duckOnInterruption = this[DUCK_ON_INTERRUPTION] ?: DEFAULTS.audio.duckOnInterruption,
        ),
        sleep = SleepSettings(
            fadeSeconds = this[SLEEP_FADE] ?: DEFAULTS.sleep.fadeSeconds,
            shakeToExtend = this[SLEEP_SHAKE] ?: DEFAULTS.sleep.shakeToExtend,
            shakeSensitivity = this[SLEEP_SHAKE_SENSITIVITY] ?: DEFAULTS.sleep.shakeSensitivity,
            extendMinutes = this[SLEEP_EXTEND] ?: DEFAULTS.sleep.extendMinutes,
            rewindOnWakeSec = this[SLEEP_REWIND] ?: DEFAULTS.sleep.rewindOnWakeSec,
            survivesPause = this[SLEEP_SURVIVES_PAUSE] ?: DEFAULTS.sleep.survivesPause,
        ),
        route = RouteSettings(
            pauseOnDisconnect = this[PAUSE_ON_DISCONNECT] ?: DEFAULTS.route.pauseOnDisconnect,
            resumeOnHeadphones = this[RESUME_HEADPHONES] ?: DEFAULTS.route.resumeOnHeadphones,
            resumeInCar = this[RESUME_CAR] ?: DEFAULTS.route.resumeInCar,
        ),
        autoPlay = AutoPlaySettings(
            enabled = this[AUTO_PLAY_ENABLED] ?: DEFAULTS.autoPlay.enabled,
            waitSec = this[AUTO_PLAY_WAIT] ?: DEFAULTS.autoPlay.waitSec,
            // Sorted by name so the settings list does not reorder itself between visits:
            // a preference set has no order of its own.
            devices = this[AUTO_PLAY_DEVICES].orEmpty()
                .mapNotNull(AutoPlay::decode)
                .sortedBy { it.name.lowercase() },
        ),
        skip = SkipSettings(
            defaultTrim = PodcastTrim(
                introSec = this[TRIM_INTRO] ?: DEFAULTS.skip.defaultTrim.introSec,
                outroSec = this[TRIM_OUTRO] ?: DEFAULTS.skip.defaultTrim.outroSec,
                skipMarkedAdverts = this[TRIM_ADVERTS] ?: DEFAULTS.skip.defaultTrim.skipMarkedAdverts,
            ),
            announceSkips = this[ANNOUNCE_SKIPS] ?: DEFAULTS.skip.announceSkips,
        ),
        stream = StreamSettings(
            bufferAheadMinutes = this[BUFFER_AHEAD] ?: DEFAULTS.stream.bufferAheadMinutes,
            retainStreamedMb = this[RETAIN_STREAMED] ?: DEFAULTS.stream.retainStreamedMb,
        ),
    )

    /** Order survives the round trip, because for the notification it is the setting. */
    private fun String.toButtons(): List<TransportButton> =
        split(',').mapNotNull { TransportButton.fromId(it.trim()) }.distinct()

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

    /** Trim is keyed on the podcast for the same reason speed is: the show, not the episode. */
    private fun trimSetKey(podcastId: String) = booleanPreferencesKey("trim_set_$podcastId")
    private fun trimIntroKey(podcastId: String) = intPreferencesKey("trim_intro_$podcastId")
    private fun trimOutroKey(podcastId: String) = intPreferencesKey("trim_outro_$podcastId")
    private fun trimAdvertsKey(podcastId: String) = booleanPreferencesKey("trim_adverts_$podcastId")

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
        val DUCK_ON_INTERRUPTION = booleanPreferencesKey("duck_on_interruption")
        val SLEEP_SURVIVES_PAUSE = booleanPreferencesKey("sleep_survives_pause")
        val SLEEP_FADE = intPreferencesKey("sleep_fade_seconds")
        val SLEEP_SHAKE = booleanPreferencesKey("sleep_shake_to_extend")
        val SLEEP_SHAKE_SENSITIVITY = intPreferencesKey("sleep_shake_sensitivity")
        val SLEEP_EXTEND = intPreferencesKey("sleep_extend_minutes")
        val SLEEP_REWIND = intPreferencesKey("sleep_rewind_on_wake_sec")
        val PAUSE_ON_DISCONNECT = booleanPreferencesKey("pause_on_disconnect")
        val RESUME_HEADPHONES = booleanPreferencesKey("resume_on_headphones")
        val RESUME_CAR = booleanPreferencesKey("resume_in_car")
        val NOTIFICATION_PERSISTENCE = stringPreferencesKey("notification_persistence")
        val HEADSET_NEXT = stringPreferencesKey("headset_next_action")
        val HEADSET_PREVIOUS = stringPreferencesKey("headset_previous_action")
        val TRIM_INTRO = intPreferencesKey("trim_default_intro_sec")
        val TRIM_OUTRO = intPreferencesKey("trim_default_outro_sec")
        val TRIM_ADVERTS = booleanPreferencesKey("trim_default_adverts")
        val ANNOUNCE_SKIPS = booleanPreferencesKey("announce_skips")
        val BUFFER_AHEAD = intPreferencesKey("buffer_ahead_minutes")
        val RETAIN_STREAMED = intPreferencesKey("retain_streamed_mb")
        val AUTO_PLAY_ENABLED = booleanPreferencesKey("auto_play_enabled")
        val AUTO_PLAY_WAIT = intPreferencesKey("auto_play_wait_sec")
        val AUTO_PLAY_DEVICES = stringSetPreferencesKey("auto_play_devices")
    }
}
