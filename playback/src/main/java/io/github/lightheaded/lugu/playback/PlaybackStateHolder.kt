package io.github.lightheaded.lugu.playback

import io.github.lightheaded.lugu.core.model.AudioTrack
import io.github.lightheaded.lugu.core.model.Chapter
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
}
