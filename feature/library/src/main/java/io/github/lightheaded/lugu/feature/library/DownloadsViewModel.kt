package io.github.lightheaded.lugu.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.download.DownloadRepository
import io.github.lightheaded.lugu.core.download.DownloadStatus
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.DownloadPrefs
import io.github.lightheaded.lugu.core.sync.DownloadSettings
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DownloadsUiState(
    val downloads: List<DownloadStatus> = emptyList(),
    val bytesUsed: Long = 0,
    val settings: DownloadSettings = DownloadSettings(),
) {
    val active: List<DownloadStatus> get() = downloads.filter { it.isActive }
    val complete: List<DownloadStatus> get() = downloads.filter { it.isComplete }
    val failed: List<DownloadStatus> get() = downloads.filter { it.isFailed }

    val fractionOfCap: Float
        get() = if (settings.storageCapBytes > 0) {
            (bytesUsed.toDouble() / settings.storageCapBytes).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val downloadRepository: DownloadRepository,
    downloadPrefs: DownloadPrefs,
) : ViewModel() {

    private val account: StateFlow<ActiveAccount?> =
        authRepository.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<DownloadsUiState> = account
        .flatMapLatest { current ->
            if (current == null) {
                flowOf(DownloadsUiState())
            } else {
                combine(
                    downloadRepository.observeAll(current),
                    downloadRepository.observeBytesUsed(current),
                    downloadPrefs.settings,
                ) { downloads, bytes, settings ->
                    DownloadsUiState(downloads, bytes, settings)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    fun remove(status: DownloadStatus) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            downloadRepository.remove(current, status.libraryItemId, status.episodeId)
        }
    }

    fun retry(status: DownloadStatus) {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            downloadRepository.download(current, status.libraryItemId, status.episodeId)
        }
    }
}
