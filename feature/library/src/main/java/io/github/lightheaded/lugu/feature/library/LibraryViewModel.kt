package io.github.lightheaded.lugu.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.model.Library
import io.github.lightheaded.lugu.core.model.LibraryItem
import io.github.lightheaded.lugu.core.model.MediaProgress
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.LibraryRepository
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import io.github.lightheaded.lugu.core.sync.Shelf
import io.github.lightheaded.lugu.core.sync.ShelfKind
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryRow(
    val item: LibraryItem,
    val progress: MediaProgress?,
) {
    val progressFraction: Float
        get() = progress?.progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f
}

/** A computed row of the home screen, already paired with progress. */
data class ShelfRow(val kind: ShelfKind, val rows: List<LibraryRow>)

data class LibraryUiState(
    val libraries: List<Library> = emptyList(),
    val selectedLibraryId: String? = null,
    val shelves: List<ShelfRow> = emptyList(),
    val items: List<LibraryRow> = emptyList(),
    val query: String = "",
    val isSyncing: Boolean = false,
    val syncMessage: String? = null,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val libraryRepository: LibraryRepository,
    private val progressRepository: ProgressRepository,
) : ViewModel() {

    private val selectedLibraryId = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")
    private val syncing = MutableStateFlow(false)
    private val syncMessage = MutableStateFlow<String?>(null)
    private val error = MutableStateFlow<String?>(null)

    private val account: StateFlow<ActiveAccount?> =
        authRepository.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val libraries: StateFlow<List<Library>> = account
        .flatMapLatest { current ->
            if (current == null) flowOf(emptyList()) else libraryRepository.observeLibraries(current)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val progressByKey: StateFlow<Map<String, MediaProgress>> = account
        .flatMapLatest { current ->
            if (current == null) flowOf(emptyList()) else progressRepository.observeAll(current)
        }
        .map { list -> list.associateBy { "${it.libraryItemId}#${it.episodeId.orEmpty()}" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val items: StateFlow<List<LibraryItem>> =
        combine(account, selectedLibraryId, query) { current, libraryId, text ->
            Triple(current, libraryId, text)
        }.flatMapLatest { (current, libraryId, text) ->
            when {
                current == null || libraryId == null -> flowOf(emptyList())
                text.isBlank() -> libraryRepository.observeItems(current, libraryId)
                else -> libraryRepository.search(current, libraryId, text)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val shelves: StateFlow<List<Shelf>> = account
        .flatMapLatest { current ->
            if (current == null) flowOf(emptyList()) else libraryRepository.observeShelves(current)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<LibraryUiState> = combine(
        libraries,
        selectedLibraryId,
        items,
        shelves,
        combine(progressByKey, query, syncing, syncMessage, error) { progress, text, isSyncing, message, err ->
            Extras(progress, text, isSyncing, message, err)
        },
    ) { libs, selected, itemList, shelfList, extras ->
        LibraryUiState(
            libraries = libs,
            selectedLibraryId = selected,
            shelves = shelfList.map { shelf ->
                ShelfRow(shelf.kind, shelf.items.map { LibraryRow(it, extras.progress["${it.id}#"]) })
            },
            items = itemList.map { LibraryRow(it, extras.progress["${it.id}#"]) },
            query = extras.query,
            isSyncing = extras.isSyncing,
            syncMessage = extras.message,
            error = extras.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    private data class Extras(
        val progress: Map<String, MediaProgress>,
        val query: String,
        val isSyncing: Boolean,
        val message: String?,
        val error: String?,
    )

    init {
        // Pick a library as soon as the mirror knows about one, so the first frame
        // after sign-in has content rather than an empty state.
        viewModelScope.launch {
            libraries.collect { available ->
                if (selectedLibraryId.value == null && available.isNotEmpty()) {
                    selectedLibraryId.value = account.value?.defaultLibraryId
                        ?.takeIf { id -> available.any { it.id == id } }
                        ?: available.first().id
                }
            }
        }
        refresh()
    }

    fun selectLibrary(libraryId: String) {
        selectedLibraryId.value = libraryId
        refresh()
    }

    fun onQueryChange(value: String) = query.update { value }

    /**
     * Re-mirrors from the server. The UI never waits on this: everything on screen is
     * already rendering from Room, so a failure here is a message, not an empty screen.
     */
    fun refresh() {
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            syncing.value = true
            error.value = null

            libraryRepository.syncLibraries(current)
                .onFailure { error.value = it.message ?: "Could not reach the server" }

            // Sync the library in view first; the rest catch up on the periodic sweep.
            val targets = listOfNotNull(selectedLibraryId.value)
            targets.forEach { id ->
                libraryRepository.syncLibraryItems(current, id) { synced, total ->
                    syncMessage.value = "Syncing $synced of $total"
                }.onFailure { error.value = it.message ?: "Could not sync the library" }
            }

            progressRepository.reconcile(current)
            syncMessage.value = null
            syncing.value = false
        }
    }

    /**
     * Cover URLs are plain server URLs; the shared OkHttp interceptor attaches auth,
     * so Coil needs nothing special beyond the address.
     */
    fun coverUrl(itemId: String, width: Int = 400): String? =
        account.value?.let { "${it.baseUrl}/api/items/$itemId/cover?width=$width" }
}
