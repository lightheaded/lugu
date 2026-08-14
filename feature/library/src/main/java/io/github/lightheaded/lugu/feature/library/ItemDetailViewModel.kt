package io.github.lightheaded.lugu.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.model.LibraryItem
import io.github.lightheaded.lugu.core.model.MediaProgress
import io.github.lightheaded.lugu.core.model.PodcastEpisode
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.LibraryRepository
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EpisodeRow(
    val episode: PodcastEpisode,
    val progress: MediaProgress?,
) {
    val progressFraction: Float
        get() = progress?.progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f
}

data class ItemDetailUiState(
    val item: LibraryItem? = null,
    val episodes: List<EpisodeRow> = emptyList(),
    val progressFraction: Float = 0f,
    val positionSec: Double = 0.0,
    val coverUrl: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ItemDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
    private val libraryRepository: LibraryRepository,
    private val progressRepository: ProgressRepository,
) : ViewModel() {

    private val itemId: String = checkNotNull(savedStateHandle["itemId"])

    private val account: StateFlow<ActiveAccount?> =
        authRepository.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<ItemDetailUiState> = account
        .flatMapLatest { current ->
            if (current == null) {
                flowOf(ItemDetailUiState())
            } else {
                combine(
                    libraryRepository.observeItem(current, itemId),
                    libraryRepository.observeEpisodes(current, itemId),
                    progressRepository.observeAll(current),
                ) { item, episodes, progress ->
                    val byKey = progress.associateBy { "${it.libraryItemId}#${it.episodeId.orEmpty()}" }
                    val itemProgress = byKey["$itemId#"]
                    ItemDetailUiState(
                        item = item,
                        episodes = episodes.map { EpisodeRow(it, byKey["$itemId#${it.id}"]) },
                        progressFraction = itemProgress?.progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f,
                        positionSec = itemProgress?.currentTimeSec ?: 0.0,
                        coverUrl = "${current.baseUrl}/api/items/$itemId/cover?width=600",
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ItemDetailUiState())

    init {
        // The list payload is minified; chapters and episodes only arrive with the
        // expanded fetch, so pull it once on open and let Room feed the screen.
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            libraryRepository.syncItemDetail(current, itemId)
        }
    }
}
