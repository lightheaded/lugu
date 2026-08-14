package io.github.lightheaded.lugu.playback

import io.github.lightheaded.lugu.core.model.AudioTrack
import io.github.lightheaded.lugu.core.model.Chapter
import io.github.lightheaded.lugu.core.model.SleepMode
import io.github.lightheaded.lugu.core.model.SleepTimerState
import io.github.lightheaded.lugu.core.sync.ProgressJump
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What is loaded in the player right now, shared between the service and the UI. */
data class NowPlaying(
    val libraryItemId: String,
    val episodeId: String?,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val durationSec: Double,
    val tracks: List<AudioTrack>,
    val chapters: List<Chapter>,
    val ledgerId: String,
    val isTranscoded: Boolean,
)

/**
 * Playback context that outlives any single MediaItem.
 *
 * ExoPlayer knows about a playlist of files; it does not know which book that is,
 * which session is recording the listening, or where the chapter boundaries fall.
 * That lives here, in one process-wide place both the service and the UI read.
 */
@Singleton
class PlaybackStateHolder @Inject constructor() {
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _sleepTimer = MutableStateFlow(SleepTimerState())

    /** The sleep timer lives app-side, not in the audio pipeline, so it also works when casting. */
    val sleepTimer: StateFlow<SleepTimerState> = _sleepTimer.asStateFlow()

    /** Whole-book position at which the timer was armed; a duration counts from here. */
    @Volatile var sleepArmedAtPositionSec: Double = 0.0
        private set

    private val _rewindNotice = MutableStateFlow<String?>(null)

    /** "Rewound 12s" after a resume, so an automatic correction is never invisible. */
    val rewindNotice: StateFlow<String?> = _rewindNotice.asStateFlow()

    private val _pendingJump = MutableStateFlow<ProgressJump?>(null)

    /** A position adopted from another device, waiting to be shown to the user with an undo. */
    val pendingJump: StateFlow<ProgressJump?> = _pendingJump.asStateFlow()

    fun set(nowPlaying: NowPlaying?) {
        _nowPlaying.value = nowPlaying
    }

    fun setJump(jump: ProgressJump?) {
        _pendingJump.value = jump
    }

    fun clearJump() {
        _pendingJump.update { null }
    }

    fun armSleepTimer(mode: SleepMode?, atPositionSec: Double) {
        sleepArmedAtPositionSec = atPositionSec
        _sleepTimer.value = SleepTimerState(mode = mode)
    }

    fun updateSleepTimer(remainingSec: Double?, isFading: Boolean) {
        val current = _sleepTimer.value
        if (current.remainingSec == remainingSec && current.isFading == isFading) return
        _sleepTimer.value = current.copy(remainingSec = remainingSec, isFading = isFading)
    }

    fun clearSleepTimer() {
        _sleepTimer.value = SleepTimerState()
    }

    fun setRewindNotice(text: String?) {
        _rewindNotice.value = text
    }

    fun clearRewindNotice() {
        _rewindNotice.value = null
    }
}
