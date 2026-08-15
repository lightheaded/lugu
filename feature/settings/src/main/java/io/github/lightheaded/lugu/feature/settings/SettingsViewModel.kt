package io.github.lightheaded.lugu.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.DownloadPrefs
import io.github.lightheaded.lugu.core.sync.DownloadSettings
import io.github.lightheaded.lugu.core.sync.PlaybackPrefs
import io.github.lightheaded.lugu.core.sync.PlayerSettings
import io.github.lightheaded.lugu.core.sync.TransportButton
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: PlayerSettings = PlayerSettings(),
    val downloads: DownloadSettings = DownloadSettings(),
    val account: ActiveAccount? = null,
    val query: String = "",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PlaybackPrefs,
    private val downloadPrefs: DownloadPrefs,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")

    val state: StateFlow<SettingsUiState> = combine(
        prefs.settings,
        downloadPrefs.settings,
        authRepository.observeAccount(),
        query,
    ) { settings, downloads, account, text ->
        SettingsUiState(settings, downloads, account, text)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onQueryChange(value: String) = query.update { value }

    fun setWifiOnly(enabled: Boolean) = viewModelScope.launch { downloadPrefs.setWifiOnly(enabled) }

    fun setRequiresCharging(enabled: Boolean) =
        viewModelScope.launch { downloadPrefs.setRequiresCharging(enabled) }

    fun setStorageCap(bytes: Long) = viewModelScope.launch { downloadPrefs.setStorageCapBytes(bytes) }

    fun setAutoDeleteFinishedAfterDays(days: Int) =
        viewModelScope.launch { downloadPrefs.setAutoDeleteFinishedAfterDays(days) }

    fun setSkipBack(seconds: Int) = viewModelScope.launch { prefs.setSkipBack(seconds) }

    fun setSkipForward(seconds: Int) = viewModelScope.launch { prefs.setSkipForward(seconds) }

    fun setNoticeSeconds(seconds: Int) = viewModelScope.launch { prefs.setNoticeSeconds(seconds) }

    fun togglePlayerButton(button: TransportButton) = viewModelScope.launch {
        val current = state.value.settings.playerButtons
        prefs.setPlayerButtons(if (button in current) current - button else current + button)
    }

    fun toggleNotificationButton(button: TransportButton) = viewModelScope.launch {
        val current = state.value.settings.notificationButtons
        prefs.setNotificationButtons(if (button in current) current - button else current + button)
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

    fun signOut() = viewModelScope.launch { authRepository.logout() }
}
