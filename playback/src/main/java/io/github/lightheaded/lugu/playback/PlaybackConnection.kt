package io.github.lightheaded.lugu.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lightheaded.lugu.core.model.Chapter
import io.github.lightheaded.lugu.core.model.Chapters
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.LibraryRepository
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the player screen renders. Positions are whole-book seconds, never per-track. */
data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val speed: Float = 1.0f,
    val chapter: Chapter? = null,
    val error: String? = null,
)

/**
 * The UI's handle on the playback service.
 *
 * Deliberately thin: it issues transport commands and reports state. Every decision
 * that must hold on *all* surfaces — which file to play, when to persist, how to
 * resolve conflicting progress — lives in the service and the resolver instead, so
 * Android Auto and the notification get the same behaviour for free.
 */
@Singleton
class PlaybackConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaResolver: MediaResolver,
    private val authRepository: AuthRepository,
    private val libraryRepository: LibraryRepository,
    private val progressRepository: ProgressRepository,
    private val stateHolder: PlaybackStateHolder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectMutex = Mutex()
    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    val nowPlaying: StateFlow<NowPlaying?> get() = stateHolder.nowPlaying
    val pendingJump get() = stateHolder.pendingJump
    val rewindNotice get() = stateHolder.rewindNotice

    fun dismissRewindNotice() = stateHolder.clearRewindNotice()

    init {
        scope.launch {
            while (true) {
                delay(POLL_MS)
                pushState()
            }
        }
    }

    private suspend fun controller(): MediaController = connectMutex.withLock {
        controller?.takeIf { it.isConnected }?.let { return it }
        val token = SessionToken(context, ComponentName(context, LuguPlaybackService::class.java))
        val created = MediaController.Builder(context, token).buildAsync().await()
        created.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = pushState()

                override fun onPlaybackStateChanged(playbackState: Int) = pushState()

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    _state.value = _state.value.copy(error = error.errorCodeName)
                }
            },
        )
        controller = created
        created
    }

    private fun pushState() {
        val player = controller ?: return
        val context = stateHolder.nowPlaying.value
        val positionSec = if (context != null) {
            AbsoluteTiming.toAbsoluteSec(
                context.tracks,
                player.currentMediaItemIndex,
                player.currentPosition.coerceAtLeast(0),
            )
        } else {
            player.currentPosition / 1000.0
        }
        _state.value = _state.value.copy(
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionSec = positionSec,
            durationSec = context?.durationSec ?: (player.duration.takeIf { it > 0 }?.div(1000.0) ?: 0.0),
            speed = player.playbackParameters.speed,
            chapter = context?.chapters?.let { Chapters.at(it, positionSec) },
        )
    }

    /** Starts an item from wherever the sync engine says it should start. */
    fun play(libraryItemId: String, episodeId: String? = null) {
        scope.launch {
            _state.value = _state.value.copy(isBuffering = true, error = null)
            val account = authRepository.account() ?: return@launch

            val resolved = withContext(Dispatchers.IO) {
                mediaResolver.resolve(account, libraryItemId, episodeId)
            }.getOrElse { failure ->
                _state.value = _state.value.copy(
                    isBuffering = false,
                    error = failure.message ?: "Could not start playback",
                )
                return@launch
            }

            val chapters = withContext(Dispatchers.IO) {
                runCatching {
                    libraryRepository.chapters(account, libraryItemId)
                        .map { Chapter(it.chapterIndex, it.startSec, it.endSec, it.title) }
                }.getOrDefault(emptyList())
            }
            val coverUrl = withContext(Dispatchers.IO) {
                runCatching { libraryRepository.coverUrl(libraryItemId, 600) }.getOrNull()
            }

            stateHolder.set(
                NowPlaying(
                    libraryItemId = libraryItemId,
                    episodeId = episodeId,
                    title = resolved.session.title,
                    author = resolved.session.author,
                    coverUrl = coverUrl,
                    durationSec = resolved.session.durationSec,
                    tracks = resolved.session.tracks,
                    chapters = chapters.ifEmpty { resolved.session.chapters },
                    ledgerId = resolved.ledgerId,
                    isTranscoded = resolved.session.isTranscoded,
                ),
            )
            stateHolder.setJump(resolved.jump)

            val start = AbsoluteTiming.toTrack(resolved.session.tracks, resolved.startPositionSec)
            val player = controller()
            player.setMediaItems(resolved.mediaItems, start.trackIndex, start.positionMs)
            player.prepare()
            player.play()
            pushState()
        }
    }

    fun togglePlayPause() = withController { if (it.isPlaying) it.pause() else it.play() }

    fun pause() = withController { it.pause() }

    /**
     * Relative seek in whole-book seconds. Crossing a file boundary is handled here
     * rather than by the player, so "back 30 seconds" at the start of a track lands in
     * the previous file instead of stopping at zero.
     */
    fun seekBy(deltaSec: Double) {
        scope.launch {
            val player = controller()
            val context = stateHolder.nowPlaying.value
            val current = _state.value.positionSec
            val target = (current + deltaSec).coerceIn(0.0, maxOf(_state.value.durationSec, 0.0))
            if (context == null || context.tracks.size <= 1) {
                player.seekTo((target * 1000).toLong())
            } else {
                val position = AbsoluteTiming.toTrack(context.tracks, target)
                player.seekTo(position.trackIndex, position.positionMs)
            }
            pushState()
        }
    }

    fun seekTo(absoluteSec: Double) {
        scope.launch {
            val player = controller()
            val context = stateHolder.nowPlaying.value
            if (context == null || context.tracks.size <= 1) {
                player.seekTo((absoluteSec * 1000).toLong())
            } else {
                val position = AbsoluteTiming.toTrack(context.tracks, absoluteSec)
                player.seekTo(position.trackIndex, position.positionMs)
            }
            pushState()
        }
    }

    fun setSpeed(speed: Float) = withController { it.setPlaybackSpeed(speed.coerceIn(0.5f, 3.5f)) }

    /** Puts the position back where it was before a jump was adopted from another device. */
    fun undoJump() {
        scope.launch {
            val jump = stateHolder.pendingJump.value ?: return@launch
            val account = authRepository.account() ?: return@launch
            val duration = stateHolder.nowPlaying.value?.durationSec ?: 0.0
            withContext(Dispatchers.IO) { progressRepository.revertJump(account, jump, duration) }
            seekTo(jump.fromSec)
            stateHolder.clearJump()
        }
    }

    fun dismissJump() = stateHolder.clearJump()

    private fun withController(block: (MediaController) -> Unit) {
        scope.launch {
            block(controller())
            pushState()
        }
    }

    private companion object {
        const val POLL_MS = 500L
    }
}
