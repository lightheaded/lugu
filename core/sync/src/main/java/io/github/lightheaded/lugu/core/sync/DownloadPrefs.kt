package io.github.lightheaded.lugu.core.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.downloadPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "lugu_downloads")

/**
 * How aggressively lugu is allowed to use someone's phone.
 *
 * Every default here is the cautious one. A 40-hour audiobook is a gigabyte or two, and
 * an app that helps itself to a metered connection or fills the last of the storage is
 * an app that gets uninstalled — so unmetered-only is on by default and there is always
 * a cap.
 */
data class DownloadSettings(
    val wifiOnly: Boolean = true,
    val requiresCharging: Boolean = false,
    val storageCapBytes: Long = DEFAULT_CAP_BYTES,
    /** Days after finishing a book before its download is reclaimed; 0 means never. */
    val autoDeleteFinishedAfterDays: Int = 0,
    /** Download whatever is in the queue, so what plays next is already on the phone. */
    val autoDownloadQueue: Boolean = false,
    /** How many unstarted volumes of an in-progress series to keep ready; 0 means none. */
    val autoDownloadNextInSeries: Int = 0,
    /** How many recent episodes of a podcast being listened to to keep ready; 0 means none. */
    val autoDownloadLatestEpisodes: Int = 0,
    /** Say when a podcast being listened to has published something. */
    val notifyNewEpisodes: Boolean = false,
) {
    val hasAutoDownloadRule: Boolean
        get() = autoDownloadQueue || autoDownloadNextInSeries > 0 || autoDownloadLatestEpisodes > 0

    companion object {
        const val DEFAULT_CAP_BYTES = 8L * 1024 * 1024 * 1024

        /** Offered as one-tap choices in settings. */
        val CAP_CHOICES_BYTES = listOf(
            2L * 1024 * 1024 * 1024,
            4L * 1024 * 1024 * 1024,
            8L * 1024 * 1024 * 1024,
            16L * 1024 * 1024 * 1024,
            32L * 1024 * 1024 * 1024,
        )

        val AUTO_DELETE_CHOICES_DAYS = listOf(0, 1, 7, 30)

        /** Small numbers on purpose: a rule that fills the cap is a rule that stops working. */
        val AUTO_DOWNLOAD_COUNT_CHOICES = listOf(0, 1, 2, 3, 5)
    }
}

@Singleton
class DownloadPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.downloadPrefsStore

    val settings: Flow<DownloadSettings> = store.data.map { it.toSettings() }

    suspend fun current(): DownloadSettings = settings.first()

    suspend fun setWifiOnly(enabled: Boolean) {
        store.edit { it[WIFI_ONLY] = enabled }
    }

    suspend fun setRequiresCharging(enabled: Boolean) {
        store.edit { it[REQUIRES_CHARGING] = enabled }
    }

    suspend fun setStorageCapBytes(bytes: Long) {
        store.edit { it[STORAGE_CAP] = bytes.coerceAtLeast(MIN_CAP_BYTES) }
    }

    suspend fun setAutoDeleteFinishedAfterDays(days: Int) {
        store.edit { it[AUTO_DELETE_DAYS] = days.coerceIn(0, 365) }
    }

    suspend fun setAutoDownloadQueue(enabled: Boolean) {
        store.edit { it[AUTO_DOWNLOAD_QUEUE] = enabled }
    }

    suspend fun setAutoDownloadNextInSeries(count: Int) {
        store.edit { it[AUTO_DOWNLOAD_SERIES] = count.coerceIn(0, MAX_AUTO_DOWNLOAD) }
    }

    suspend fun setAutoDownloadLatestEpisodes(count: Int) {
        store.edit { it[AUTO_DOWNLOAD_EPISODES] = count.coerceIn(0, MAX_AUTO_DOWNLOAD) }
    }

    suspend fun setNotifyNewEpisodes(enabled: Boolean) {
        store.edit { it[NOTIFY_NEW_EPISODES] = enabled }
    }

    private fun Preferences.toSettings() = DownloadSettings(
        wifiOnly = this[WIFI_ONLY] ?: DEFAULTS.wifiOnly,
        requiresCharging = this[REQUIRES_CHARGING] ?: DEFAULTS.requiresCharging,
        storageCapBytes = this[STORAGE_CAP] ?: DEFAULTS.storageCapBytes,
        autoDeleteFinishedAfterDays = this[AUTO_DELETE_DAYS] ?: DEFAULTS.autoDeleteFinishedAfterDays,
        autoDownloadQueue = this[AUTO_DOWNLOAD_QUEUE] ?: DEFAULTS.autoDownloadQueue,
        autoDownloadNextInSeries = this[AUTO_DOWNLOAD_SERIES] ?: DEFAULTS.autoDownloadNextInSeries,
        autoDownloadLatestEpisodes = this[AUTO_DOWNLOAD_EPISODES] ?: DEFAULTS.autoDownloadLatestEpisodes,
        notifyNewEpisodes = this[NOTIFY_NEW_EPISODES] ?: DEFAULTS.notifyNewEpisodes,
    )

    private companion object {
        val DEFAULTS = DownloadSettings()
        const val MIN_CAP_BYTES = 512L * 1024 * 1024
        const val MAX_AUTO_DOWNLOAD = 10

        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val REQUIRES_CHARGING = booleanPreferencesKey("requires_charging")
        val STORAGE_CAP = longPreferencesKey("storage_cap_bytes")
        val AUTO_DELETE_DAYS = intPreferencesKey("auto_delete_finished_days")
        val AUTO_DOWNLOAD_QUEUE = booleanPreferencesKey("auto_download_queue")
        val AUTO_DOWNLOAD_SERIES = intPreferencesKey("auto_download_next_in_series")
        val AUTO_DOWNLOAD_EPISODES = intPreferencesKey("auto_download_latest_episodes")
        val NOTIFY_NEW_EPISODES = booleanPreferencesKey("notify_new_episodes")
    }
}
