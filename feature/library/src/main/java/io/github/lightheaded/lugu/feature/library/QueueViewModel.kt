package io.github.lightheaded.lugu.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.QueueItem
import io.github.lightheaded.lugu.core.sync.QueuePrefs
import io.github.lightheaded.lugu.core.sync.QueueRepository
import io.github.lightheaded.lugu.core.sync.QueueSettings
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class QueueUiState(
    val items: List<QueueItem> = emptyList(),
    val settings: QueueSettings = QueueSettings(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class QueueViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val queueRepository: QueueRepository,
    private val queuePrefs: QueuePrefs,
) : ViewModel() {

    private val account: StateFlow<ActiveAccount?> =
        authRepository.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<QueueUiState> = account
        .flatMapLatest { current ->
            if (current == null) {
                flowOf(QueueUiState())
            } else {
                combine(queueRepository.observe(current), queuePrefs.settings) { items, settings ->
                    QueueUiState(items, settings)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueUiState())

    fun remove(item: QueueItem) = withAccount { queueRepository.remove(it, item.libraryItemId, item.episodeId) }

    fun move(from: Int, to: Int) = withAccount { queueRepository.move(it, from, to) }

    fun clear() = withAccount { queueRepository.clear(it) }

    fun setContinueSeries(enabled: Boolean) = viewModelScope.launch { queuePrefs.setContinueSeries(enabled) }

    fun setContinuePodcast(enabled: Boolean) = viewModelScope.launch { queuePrefs.setContinuePodcast(enabled) }

    fun setAskFirst(enabled: Boolean) = viewModelScope.launch { queuePrefs.setAskBeforeSuggestion(enabled) }

    private fun withAccount(block: suspend (ActiveAccount) -> Unit) {
        viewModelScope.launch { authRepository.account()?.let { block(it) } }
    }
}
