package io.github.lightheaded.lugu.feature.settings

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.model.AutoPlayDevice
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.ConnectionPrefs
import io.github.lightheaded.lugu.core.sync.CrashReportingPrefs
import io.github.lightheaded.lugu.core.sync.CredentialKind
import io.github.lightheaded.lugu.core.sync.CredentialLossReport
import io.github.lightheaded.lugu.core.sync.DownloadPrefs
import io.github.lightheaded.lugu.core.sync.DownloadSettings
import io.github.lightheaded.lugu.core.sync.HeadsetAction
import io.github.lightheaded.lugu.core.sync.LibraryPrefs
import io.github.lightheaded.lugu.core.sync.NotificationPersistence
import io.github.lightheaded.lugu.core.sync.LibrarySettings
import io.github.lightheaded.lugu.core.sync.PlaybackPrefs
import io.github.lightheaded.lugu.core.sync.PlayerSettings
import io.github.lightheaded.lugu.core.sync.QueuePrefs
import io.github.lightheaded.lugu.core.sync.QueueSettings
import io.github.lightheaded.lugu.core.sync.ShelfKind
import io.github.lightheaded.lugu.core.sync.StartTab
import io.github.lightheaded.lugu.core.sync.TransportButton
import io.github.lightheaded.lugu.playback.CompanionDevices
import io.github.lightheaded.lugu.playback.PairedDevices
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: PlayerSettings = PlayerSettings(),
    val downloads: DownloadSettings = DownloadSettings(),
    val account: ActiveAccount? = null,
    val crashReporting: Boolean = false,
    val queue: QueueSettings = QueueSettings(),
    val library: LibrarySettings = LibrarySettings(),
    val query: String = "",
    /** What went wrong the last time a device was being chosen, if anything. */
    val autoPlayMessage: String? = null,
    /**
     * Why the app asked for a password again, when encrypted storage is the reason.
     *
     * Null in every ordinary case. Set only after a store was rebuilt because it could not
     * be decrypted — see `EncryptedTokenStore`. A login screen with no explanation reads as
     * "the app forgot me", which is the wrong lesson to teach about a store that just
     * protected itself.
     */
    val credentialLossMessage: String? = null,
    /**
     * Whether a browser sent to this server would get there under its own steam.
     *
     * False when a custom header or a client certificate is what gets lugu in, because
     * neither can be handed to another app — see `core.model.WebClient`. Worth saying before
     * the link is followed rather than after, since the failure arrives as the proxy's own
     * refusal and says nothing about lugu.
     */
    val webClientReachable: Boolean = true,
)

/** The one-off messages, gathered so the top-level `combine` keeps to five flows. */
private data class Notices(
    val autoPlayMessage: String?,
    val credentialLossMessage: String?,
)

/**
 * The stored settings, gathered into one value.
 *
 * `combine` is typed only up to five flows and there are more than five stores now.
 * Grouping them here rather than nesting combines keeps the assembly readable and means
 * adding the next store is a field rather than another layer.
 */
private data class StoredSettings(
    val player: PlayerSettings,
    val downloads: DownloadSettings,
    val queue: QueueSettings,
    val library: LibrarySettings,
    val webClientReachable: Boolean,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PlaybackPrefs,
    private val downloadPrefs: DownloadPrefs,
    private val authRepository: AuthRepository,
    private val crashReportingPrefs: CrashReportingPrefs,
    private val queuePrefs: QueuePrefs,
    private val libraryPrefs: LibraryPrefs,
    private val companionDevices: CompanionDevices,
    private val pairedDevices: PairedDevices,
    private val connectionPrefs: ConnectionPrefs,
    private val credentialLosses: CredentialLossReport = CredentialLossReport(),
) : ViewModel() {

    private val query = MutableStateFlow("")

    private val autoPlayMessage = MutableStateFlow<String?>(null)

    /**
     * The system's device picker, waiting to be launched.
     *
     * A one-shot value rather than a call, because only something with an activity can start
     * an `IntentSender` and a view model has none. The screen launches it and clears it.
     */
    private val _devicePicker = MutableStateFlow<IntentSender?>(null)
    val devicePicker: StateFlow<IntentSender?> = _devicePicker

    /** Whether choosing a device goes through the system's picker or the paired list. */
    val usesSystemDevicePicker: Boolean get() = companionDevices.isSupported

    /** Whether this phone can do any of this at all. */
    val autoPlaySupported: Boolean
        get() = companionDevices.isSupported || pairedDevices.isSupported

    /**
     * Whether anything is configured that only this app can present.
     *
     * Not keyed to the signed-in address, and deliberately: lugu holds one account, so "a
     * header is stored for some address" and "a header is stored for this one" are the same
     * question in every real case — and the wrong answer of the two is the safe one, since it
     * warns rather than staying quiet. Read off `observeCertificate`, whose revision counter
     * ticks for header writes too, on the IO dispatcher because the first read of an
     * encrypted preference file is not free.
     */
    private val webClientReachable: Flow<Boolean> = connectionPrefs.observeCertificate()
        .map { certificate -> certificate == null && connectionPrefs.configuredAddresses().isEmpty() }
        // The store is encrypted, so reading it opens the Android keystore — and that can
        // fail: it is absent under Robolectric, and the deprecated library throws on some
        // devices after a restore or a key rotation. `ConnectionPrefs` now answers "no
        // headers" instead of throwing, so this guard is the second line rather than the
        // first, and it stays: one throw here takes the whole settings screen down, which is
        // a wildly disproportionate outcome for a sentence of warning text. `true` is the
        // right fallback rather than a shrug: if this cannot be read, lugu is not presenting
        // a header or a certificate either, so a browser is on equal terms with the app.
        .catch { emit(true) }
        .flowOn(Dispatchers.IO)

    private val notices = combine(autoPlayMessage, credentialLosses.lost) { message, lost ->
        Notices(autoPlayMessage = message, credentialLossMessage = lostMessage(lost))
    }

    private val storedSettings = combine(
        prefs.settings,
        downloadPrefs.settings,
        queuePrefs.settings,
        libraryPrefs.settings,
        webClientReachable,
    ) { player, downloads, queue, library, reachable ->
        StoredSettings(player, downloads, queue, library, reachable)
    }

    val state: StateFlow<SettingsUiState> = combine(
        storedSettings,
        authRepository.observeAccount(),
        crashReportingPrefs.enabled,
        query,
        notices,
    ) { stored, account, crashReporting, text, noticed ->
        SettingsUiState(
            settings = stored.player,
            downloads = stored.downloads,
            account = account,
            crashReporting = crashReporting,
            queue = stored.queue,
            library = stored.library,
            query = text,
            autoPlayMessage = noticed.autoPlayMessage,
            credentialLossMessage = noticed.credentialLossMessage,
            webClientReachable = stored.webClientReachable,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onQueryChange(value: String) = query.update { value }

    fun setWifiOnly(enabled: Boolean) = viewModelScope.launch { downloadPrefs.setWifiOnly(enabled) }

    fun setRequiresCharging(enabled: Boolean) =
        viewModelScope.launch { downloadPrefs.setRequiresCharging(enabled) }

    fun setStorageCap(bytes: Long) = viewModelScope.launch { downloadPrefs.setStorageCapBytes(bytes) }

    fun setAutoDeleteFinishedAfterDays(days: Int) =
        viewModelScope.launch { downloadPrefs.setAutoDeleteFinishedAfterDays(days) }

    fun setAutoDownloadQueue(enabled: Boolean) =
        viewModelScope.launch { downloadPrefs.setAutoDownloadQueue(enabled) }

    fun setAutoDownloadNextInSeries(count: Int) =
        viewModelScope.launch { downloadPrefs.setAutoDownloadNextInSeries(count) }

    fun setAutoDownloadLatestEpisodes(count: Int) =
        viewModelScope.launch { downloadPrefs.setAutoDownloadLatestEpisodes(count) }

    fun setNotifyNewEpisodes(enabled: Boolean) =
        viewModelScope.launch { downloadPrefs.setNotifyNewEpisodes(enabled) }

    fun setSkipBack(seconds: Int) = viewModelScope.launch { prefs.setSkipBack(seconds) }

    fun setSkipForward(seconds: Int) = viewModelScope.launch { prefs.setSkipForward(seconds) }

    fun setNoticeSeconds(seconds: Int) = viewModelScope.launch { prefs.setNoticeSeconds(seconds) }

    fun togglePlayerButton(button: TransportButton) = viewModelScope.launch {
        val current = state.value.settings.playerButtons
        prefs.setPlayerButtons(if (button in current) current - button else current + button)
    }

    /**
     * Tap to add, tap again to remove — and the order of the taps is the order in the
     * notification. A picker that also answers "in what order" without a second control is
     * worth the small oddity of having to remove and re-add to reshuffle.
     */
    fun toggleNotificationButton(button: TransportButton) = viewModelScope.launch {
        val current = state.value.settings.notificationButtons
        prefs.setNotificationButtons(if (button in current) current - button else current + button)
    }

    fun setNotificationPersistence(value: NotificationPersistence) =
        viewModelScope.launch { prefs.setNotificationPersistence(value) }

    fun setHeadsetNextAction(action: HeadsetAction) =
        viewModelScope.launch { prefs.setHeadsetNextAction(action) }

    fun setHeadsetPreviousAction(action: HeadsetAction) =
        viewModelScope.launch { prefs.setHeadsetPreviousAction(action) }

    fun setAutoPlayEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setAutoPlayEnabled(enabled)
        // Switching it on is the moment to make sure the system is actually watching. An
        // observation can lapse — a restart, a system update — and re-asking is cheap.
        if (enabled) companionDevices.observe(state.value.settings.autoPlay.devices)
    }

    fun setAutoPlayWaitSec(seconds: Int) =
        viewModelScope.launch { prefs.setAutoPlayWaitSec(seconds) }

    fun dismissAutoPlayMessage() = autoPlayMessage.update { null }

    fun dismissCredentialLossMessage() = credentialLosses.acknowledge()

    /**
     * Says what was lost, and what to do about it, in the listener's terms.
     *
     * The cause is named because it is not the app's fault and not theirs: a device restore
     * or a lock-screen change replaces the key that the store was built on. A certificate
     * gets its own sentence, because nobody can recall one from memory.
     */
    private fun lostMessage(lost: Set<CredentialKind>): String? {
        val tokens = CredentialKind.Tokens in lost
        val connection = CredentialKind.ConnectionSettings in lost
        return when {
            tokens && connection ->
                "This device replaced the key that protects stored credentials, which happens " +
                    "after a restore. The sign-in and the connection settings are gone. Sign in " +
                    "again, and add any custom headers or client certificate again."
            tokens ->
                "This device replaced the key that protects the stored sign-in, which happens " +
                    "after a restore. Please sign in again."
            connection ->
                "This device replaced the key that protects the connection settings, which " +
                    "happens after a restore. Add any custom headers or client certificate again."
            else -> null
        }
    }

    /** Opens the system's device picker. The result comes back through [onDevicePicked]. */
    fun chooseAutoPlayDevice() {
        autoPlayMessage.update { null }
        companionDevices.requestAssociation(
            onPicker = { sender -> _devicePicker.update { sender } },
            onFailure = { message -> autoPlayMessage.update { message } },
        )
    }

    fun devicePickerLaunched() = _devicePicker.update { null }

    /**
     * The listener chose a device in the system's picker.
     *
     * Which device that was is read back from the association rather than out of the picker's
     * result — see [CompanionDevices.newlyAssociated] — so nothing here needs to understand
     * what the system handed back.
     */
    fun onDevicePicked() = viewModelScope.launch {
        val known = state.value.settings.autoPlay.devices
        val device = companionDevices.newlyAssociated(known) ?: run {
            autoPlayMessage.update { "That device was already in the list" }
            return@launch
        }
        prefs.addAutoPlayDevice(device)
        companionDevices.observe(listOf(device))
    }

    /** The paired list, on the versions of Android that use it instead of a system picker. */
    fun pairedDevices() = pairedDevices.paired()

    fun addAutoPlayDevice(device: AutoPlayDevice) =
        viewModelScope.launch { prefs.addAutoPlayDevice(device) }

    /**
     * Removes a device, and gives up the association with it.
     *
     * Both, and in that order. Leaving the association behind would leave lugu holding a
     * standing right to start itself for hardware the listener has just said they are done
     * with, which is not what removing a row from a list looks like it does.
     */
    fun removeAutoPlayDevice(device: AutoPlayDevice) = viewModelScope.launch {
        companionDevices.forget(device)
        prefs.removeAutoPlayDevice(device.key)
    }

    fun setStartTab(tab: StartTab) = viewModelScope.launch { libraryPrefs.setStartTab(tab) }

    fun setShelfHidden(name: String, hidden: Boolean) =
        viewModelScope.launch { libraryPrefs.setShelfHidden(name, hidden) }

    /**
     * Moves a shelf one place in the list as it is shown.
     *
     * The swap is with the neighbouring *visible* shelf, not the neighbouring stored one.
     * Those differ as soon as anything is switched off, and swapping with a hidden shelf
     * is a press that visibly does nothing — the worst kind of broken control, because it
     * looks like the app ignored you.
     *
     * The order is written out in full rather than as a delta, because it may still be
     * empty, meaning "however they are declared", and a delta against nothing has no
     * meaning. The first move is what fixes the current arrangement in place.
     */
    fun moveShelf(name: String, up: Boolean) = viewModelScope.launch {
        val settings = state.value.library
        val declared = ShelfKind.entries.map { it.name }
        // Anything the stored order predates is appended, so every shelf is addressable
        // even after one is added in a later version.
        val full = settings.shelfOrder.filter { it in declared } + declared.filterNot { it in settings.shelfOrder }

        val visible = settings.arrangeShelves(ShelfKind.entries) { it.name }.map { it.name }
        val position = visible.indexOf(name).takeIf { it >= 0 } ?: return@launch
        val neighbour = visible.getOrNull(if (up) position - 1 else position + 1) ?: return@launch

        val from = full.indexOf(name)
        val to = full.indexOf(neighbour)
        if (from < 0 || to < 0) return@launch
        libraryPrefs.setShelfOrder(
            full.toMutableList().apply {
                set(from, neighbour)
                set(to, name)
            },
        )
    }

    fun setDefaultSpeed(speed: Float) = viewModelScope.launch { prefs.setDefaultSpeed(speed) }

    fun setDefaultPodcastSpeed(speed: Float) = viewModelScope.launch { prefs.setDefaultPodcastSpeed(speed) }

    fun setSeparatePodcastSpeed(enabled: Boolean) =
        viewModelScope.launch { prefs.setSeparatePodcastSpeed(enabled) }

    fun setRememberPerBook(enabled: Boolean) = viewModelScope.launch { prefs.setRememberPerBook(enabled) }

    fun setRememberPerPodcast(enabled: Boolean) =
        viewModelScope.launch { prefs.setRememberPerPodcast(enabled) }

    fun addSpeedPreset(speed: Float) = viewModelScope.launch {
        prefs.setSpeedPresets(state.value.settings.speed.presets + speed)
    }

    fun removeSpeedPreset(speed: Float) = viewModelScope.launch {
        val remaining = state.value.settings.speed.presets - speed
        // Never leave the speed sheet with nothing to tap.
        if (remaining.isNotEmpty()) prefs.setSpeedPresets(remaining)
    }

    /**
     * Writes the flag only. The Application observes it and starts or stops Sentry, so
     * nothing here — and nothing in this module — needs to know the reporter exists.
     */
    fun setCrashReporting(enabled: Boolean) = crashReportingPrefs.setEnabled(enabled)

    fun setContinueSeries(enabled: Boolean) = viewModelScope.launch { queuePrefs.setContinueSeries(enabled) }

    fun setContinuePodcast(enabled: Boolean) = viewModelScope.launch { queuePrefs.setContinuePodcast(enabled) }

    fun setAskBeforeSuggestion(enabled: Boolean) =
        viewModelScope.launch { queuePrefs.setAskBeforeSuggestion(enabled) }

    fun setSkipSilence(enabled: Boolean) = viewModelScope.launch { prefs.setSkipSilence(enabled) }

    fun setDuckOnInterruption(enabled: Boolean) =
        viewModelScope.launch { prefs.setDuckOnInterruption(enabled) }

    fun setSleepSurvivesPause(enabled: Boolean) =
        viewModelScope.launch { prefs.setSleepSurvivesPause(enabled) }

    fun setPodcastOldestFirst(oldestFirst: Boolean) =
        viewModelScope.launch { queuePrefs.setPodcastOldestFirst(oldestFirst) }

    fun setVolumeBoostDb(db: Int) = viewModelScope.launch { prefs.setVolumeBoostDb(db) }

    fun setSleepFadeSeconds(seconds: Int) = viewModelScope.launch { prefs.setSleepFadeSeconds(seconds) }

    fun setShakeToExtend(enabled: Boolean) = viewModelScope.launch { prefs.setShakeToExtend(enabled) }

    fun setShakeSensitivity(level: Int) = viewModelScope.launch { prefs.setShakeSensitivity(level) }

    fun setSleepExtendMinutes(minutes: Int) = viewModelScope.launch { prefs.setSleepExtendMinutes(minutes) }

    fun setRewindOnWakeSec(seconds: Int) = viewModelScope.launch { prefs.setRewindOnWakeSec(seconds) }

    fun setPauseOnDisconnect(enabled: Boolean) = viewModelScope.launch { prefs.setPauseOnDisconnect(enabled) }

    fun setResumeOnHeadphones(enabled: Boolean) =
        viewModelScope.launch { prefs.setResumeOnHeadphones(enabled) }

    fun setResumeInCar(enabled: Boolean) = viewModelScope.launch { prefs.setResumeInCar(enabled) }

    /*
     * The trim is stored as one value, so each of these three rows reads the current one
     * and writes it back with a single field changed. Three separate keys would let two
     * rows tapped in quick succession disagree about what the other two were, and a trim
     * that half-applied is the kind of setting people report as "it forgot".
     */
    fun setDefaultTrimIntro(seconds: Int) = viewModelScope.launch {
        prefs.setDefaultTrim(state.value.settings.skip.defaultTrim.copy(introSec = seconds))
    }

    fun setDefaultTrimOutro(seconds: Int) = viewModelScope.launch {
        prefs.setDefaultTrim(state.value.settings.skip.defaultTrim.copy(outroSec = seconds))
    }

    fun setDefaultTrimAdverts(enabled: Boolean) = viewModelScope.launch {
        prefs.setDefaultTrim(state.value.settings.skip.defaultTrim.copy(skipMarkedAdverts = enabled))
    }

    fun setAnnounceSkips(enabled: Boolean) = viewModelScope.launch { prefs.setAnnounceSkips(enabled) }

    fun setBufferAheadMinutes(minutes: Int) =
        viewModelScope.launch { prefs.setBufferAheadMinutes(minutes) }

    fun setRetainStreamedMb(megabytes: Int) =
        viewModelScope.launch { prefs.setRetainStreamedMb(megabytes) }

    fun setMediaTypeHidden(mediaType: MediaType, hidden: Boolean) =
        viewModelScope.launch { libraryPrefs.setMediaTypeHidden(mediaType, hidden) }

    fun setShelvesFollowLibrary(enabled: Boolean) =
        viewModelScope.launch { libraryPrefs.setShelvesFollowLibrary(enabled) }

    fun signOut() = viewModelScope.launch { authRepository.logout() }
}
