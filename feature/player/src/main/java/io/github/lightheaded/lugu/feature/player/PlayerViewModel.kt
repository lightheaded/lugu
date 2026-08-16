package io.github.lightheaded.lugu.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.model.Bookmark
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.BookmarkRepository
import io.github.lightheaded.lugu.playback.PlaybackConnection
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Thin adapter over [PlaybackConnection].
 *
 * The player screen and the mini player share one instance of the connection, so both
 * report the same position and the same now-playing item — and neither of them owns
 * playback state, which keeps the service authoritative even while no UI is alive.
 *
 * Bookmarks are the one thing here that does not come from the connection. They are a
 * property of the item and the account rather than of playback, so the repository is
 * injected directly and the list is read from Room: the sheet then opens with no signal,
 * and a bookmark made in a tunnel is in it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val connection: PlaybackConnection,
    private val authRepository: AuthRepository,
    private val bookmarkRepository: BookmarkRepository,
) : ViewModel() {

    val state = connection.state
    val nowPlaying = connection.nowPlaying
    val pendingJump = connection.pendingJump
    val rewindNotice = connection.rewindNotice
    val continuation = connection.continuation
    val sleepTimer = connection.sleepTimer
    val positionHistory = connection.observePositionHistory()
    val settings = connection.settings

    private val account: StateFlow<ActiveAccount?> =
        authRepository.observeAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Whether this item can be bookmarked at all.
     *
     * Audiobookshelf has no bookmark for a podcast episode, so the control is absent for
     * one rather than present and failing.
     */
    val canBookmark: StateFlow<Boolean> = nowPlaying
        .map { it != null && it.episodeId == null }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Bookmarks for the loaded book, earliest first, from Room. */
    val bookmarks: StateFlow<List<Bookmark>> = combine(account, nowPlaying) { active, now -> active to now }
        .flatMapLatest { (active, now) ->
            if (active == null || now == null || now.episodeId != null) {
                flowOf(emptyList())
            } else {
                bookmarkRepository.observe(active, now.libraryItemId)
                    .map { list -> list.sortedBy(Bookmark::timeSec) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        /*
         * Opening an item is the moment its bookmarks matter, so that is when they are
         * refreshed. It is a refresh and not a load: the sheet is already rendering from
         * Room, so a failure here costs nothing visible, and one made on the web appears
         * without the listener having to go looking for a sync button.
         */
        viewModelScope.launch {
            nowPlaying
                .map { it?.takeIf { playing -> playing.episodeId == null }?.libraryItemId }
                .distinctUntilChanged()
                .collect { itemId ->
                    if (itemId == null) return@collect
                    val active = authRepository.account() ?: return@collect
                    withContext(Dispatchers.IO) { bookmarkRepository.sync(active) }
                }
        }
    }

    fun play(itemId: String, episodeId: String?) = connection.play(itemId, episodeId)

    fun togglePlayPause() = connection.togglePlayPause()

    fun seekBy(deltaSec: Double) = connection.seekBy(deltaSec)

    fun seekTo(absoluteSec: Double) = connection.seekTo(absoluteSec)

    fun setSpeed(speed: Float) = connection.setSpeed(speed)

    fun previousChapter() = connection.previousChapter()

    fun nextChapter() = connection.nextChapter()

    fun setSleepTimer(mode: io.github.lightheaded.lugu.core.model.SleepMode?) =
        connection.setSleepTimer(mode)

    fun extendSleepTimer(minutes: Int) = connection.extendSleepTimer(minutes)

    fun restorePosition(toSec: Double) = connection.restorePosition(toSec)

    val sleepPresets = io.github.lightheaded.lugu.core.model.SleepTimer.PRESET_MINUTES

    /**
     * Chapter counts offered as one-tap options.
     *
     * Separate from [sleepPresets] rather than folded into it because the two are not the
     * same measurement: five here is five chapters of this book, which is a different
     * amount of evening in every book.
     */
    val sleepChapterPresets = io.github.lightheaded.lugu.core.model.SleepTimer.PRESET_CHAPTERS

    /**
     * Marks the current position.
     *
     * The title is left blank on purpose: the repository names an unnamed bookmark after
     * its position, and naming it here as well would give the same bookmark two names
     * depending on where it was made.
     */
    fun addBookmark(title: String = "") = withBookmarkableItem { active, itemId ->
        bookmarkRepository.add(active, itemId, state.value.positionSec, title)
    }

    fun renameBookmark(timeSec: Long, title: String) = withBookmarkableItem { active, itemId ->
        bookmarkRepository.rename(active, itemId, timeSec, title)
    }

    fun removeBookmark(timeSec: Long) = withBookmarkableItem { active, itemId ->
        bookmarkRepository.remove(active, itemId, timeSec)
    }

    fun undoJump() = connection.undoJump()

    fun dismissJump() = connection.dismissJump()

    fun dismissRewindNotice() = connection.dismissRewindNotice()

    fun dismissContinuationNotice() = connection.dismissContinuationNotice()

    private fun withBookmarkableItem(block: suspend (ActiveAccount, String) -> Unit) {
        viewModelScope.launch {
            val now = nowPlaying.value ?: return@launch
            if (now.episodeId != null) return@launch
            val active = authRepository.account() ?: return@launch
            withContext(Dispatchers.IO) { block(active, now.libraryItemId) }
        }
    }
}
