package io.github.lightheaded.lugu.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.PlaybackPrefs
import io.github.lightheaded.lugu.core.sync.QueueItem
import io.github.lightheaded.lugu.core.sync.QueueRepository
import io.github.lightheaded.lugu.core.sync.QueueSnapshot
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * How a queue entry is named in a selection.
 *
 * The same book can sit in the queue as several episodes, so the item id alone does not
 * identify a row; the pair does, and it is already what the list uses as its key.
 */
internal val QueueItem.rowKey: String get() = "$libraryItemId#${episodeId.orEmpty()}"

data class QueueUiState(
    val items: List<QueueItem> = emptyList(),
    val selectionActive: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    /** False until Room has answered once, so "Nothing queued" does not flash on the way in. */
    val loaded: Boolean = false,
)

/**
 * A removal that has happened and can still be taken back.
 *
 * The queue is the one list in lugu that is built by hand, entry by entry, so losing it to
 * a stray tap costs work no sync can return. Confirming every removal would tax the
 * ordinary case to guard the rare one; this is the other way round — the removal happens at
 * once, and the way back stays open for as long as the notice does.
 */
data class QueueUndo(
    val text: String,
    internal val snapshot: QueueSnapshot,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class QueueViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val queueRepository: QueueRepository,
    playbackPrefs: PlaybackPrefs,
) : ViewModel() {

    private val selection = MutableStateFlow(Selection())

    private val undoable = MutableStateFlow<QueueUndo?>(null)

    /** The last removal, while it can still be taken back. */
    val undo: StateFlow<QueueUndo?> = undoable

    /**
     * How long an undo stays offered, from the same preference the player's notices use.
     *
     * One setting decides how long lugu leaves a way back, wherever the way back is. A
     * queue that argued its own timing would be a second answer to a question already
     * answered in Settings.
     */
    val noticeMillis: StateFlow<Long> = playbackPrefs.settings
        .map { it.noticeSeconds.coerceAtLeast(1) * 1_000L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 10_000L)

    // Read from the source, not from a state flow whose first null means "not known yet":
    // here a null must mean "no account", which is an answer.
    val state: StateFlow<QueueUiState> = authRepository.observeAccount()
        .flatMapLatest { current ->
            if (current == null) {
                flowOf(QueueUiState(loaded = true))
            } else {
                combine(
                    queueRepository.observe(current),
                    selection,
                ) { items, picked ->
                    // Playing through removes rows underneath the selection, so what is
                    // ticked is reconciled against what is still queued on every emission.
                    val onScreen = picked.retaining(items.map { it.rowKey })
                    QueueUiState(items, onScreen.active, onScreen.ids, loaded = true)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueUiState())

    fun remove(item: QueueItem) = withAccount { account ->
        val before = queueRepository.snapshot(account)
        queueRepository.remove(account, item.libraryItemId, item.episodeId)
        offerUndo("Removed ${item.title}", before)
    }

    fun move(from: Int, to: Int) = withAccount { queueRepository.move(it, from, to) }

    fun clear() = withAccount { account ->
        val before = queueRepository.snapshot(account)
        if (before.isEmpty) return@withAccount
        queueRepository.clear(account)
        offerUndo("Cleared the queue — ${entryCount(before.size)}", before)
    }

    /** Puts back whatever the last removal took away. */
    fun undo() {
        val pending = undoable.value ?: return
        undoable.value = null
        withAccount { queueRepository.restore(it, pending.snapshot) }
    }

    fun dismissUndo() {
        undoable.value = null
    }

    private fun offerUndo(text: String, before: QueueSnapshot) {
        undoable.value = QueueUndo(text, before)
    }

    private fun entryCount(size: Int) = if (size == 1) "1 entry" else "$size entries"

    /**
     * Selection is entered from the app bar here, not by long-press.
     *
     * Long-press on this screen already means "pick this row up and move it", which is
     * the queue's whole reason for existing. Overloading it would make both gestures
     * unreliable, so selecting is a separate, deliberate mode.
     */
    fun enterSelection() {
        selection.value = selection.value.entered()
    }

    fun toggleSelection(key: String) {
        selection.value = selection.value.toggle(key)
    }

    fun selectAll() {
        selection.value = selection.value.selectAll(state.value.items.map { it.rowKey })
    }

    fun clearSelection() {
        selection.value = selection.value.cleared()
    }

    /**
     * Removing several is one pass, not one press each.
     *
     * The rows are resolved before anything is deleted, because each removal renumbers
     * the queue and a position captured beforehand would point at the wrong entry by the
     * second iteration.
     */
    fun removeSelected() {
        val chosen = state.value.items.filter { it.rowKey in state.value.selectedIds }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val current = authRepository.account() ?: return@launch
            val before = queueRepository.snapshot(current)
            chosen.forEach { queueRepository.remove(current, it.libraryItemId, it.episodeId) }
            clearSelection()
            offerUndo("Removed ${entryCount(chosen.size)}", before)
        }
    }

    private fun withAccount(block: suspend (ActiveAccount) -> Unit) {
        viewModelScope.launch { authRepository.account()?.let { block(it) } }
    }
}
