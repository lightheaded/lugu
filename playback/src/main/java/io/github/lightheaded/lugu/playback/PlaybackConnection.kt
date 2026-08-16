package io.github.lightheaded.lugu.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lightheaded.lugu.core.model.Chapter
import io.github.lightheaded.lugu.core.model.Chapters
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.model.SleepMode
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.LibraryRepository
import io.github.lightheaded.lugu.core.sync.NotificationPersistence
import io.github.lightheaded.lugu.core.sync.PlaybackPrefs
import io.github.lightheaded.lugu.core.sync.PlayerSettings
import io.github.lightheaded.lugu.core.sync.SpeedSettings
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
    val chapterIndex: Int = -1,
    val chapterCount: Int = 0,
    /** Position within the current chapter, which is how listeners think about place. */
    val chapterPositionSec: Double = 0.0,
    val chapterDurationSec: Double = 0.0,
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
/** One recorded position change, as the recovery UI shows it. */
data class PositionJump(
    val fromSec: Double,
    val toSec: Double,
    val atMs: Long,
    val reason: String,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class PlaybackConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaResolver: MediaResolver,
    private val authRepository: AuthRepository,
    private val libraryRepository: LibraryRepository,
    private val progressRepository: ProgressRepository,
    private val playbackPrefs: PlaybackPrefs,
    private val stateHolder: PlaybackStateHolder,
    private val browseTree: BrowseTree,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectMutex = Mutex()
    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /**
     * Pending relative seek, in whole-book seconds.
     *
     * Rapid skip presses must accumulate deterministically: three taps of -10s move
     * exactly 30s, never 10 or 20. Each press adds to this offset and updates the
     * displayed position immediately, and a single seek is issued once the flurry
     * settles — reading the player's own position between presses would race, because
     * the player has not finished the previous seek yet.
     */
    private val pendingSeekMutex = Mutex()
    private var pendingSeekTargetSec: Double? = null
    private var seekJob: kotlinx.coroutines.Job? = null

    /**
     * True from the moment play is asked for until the player reports it is playing.
     *
     * Starting an item takes a round trip or two — resolving the session, reading the
     * chapters, buffering the first bytes — and for that whole time the player honestly
     * reports that it is not playing. A transport that reports the same thing invites the
     * listener to press play again, and by the time that second press lands the player
     * has started, so the toggle pauses the playback they were trying to begin.
     *
     * So the transport says "playing" on the player's behalf. That is a promise the UI is
     * making rather than a fact it has observed, and a promise has to be surrendered the
     * moment it is broken: on an error, on an explicit pause, and after [START_PROMISE_MS]
     * if the player never reports anything at all. A promise that is never surrendered
     * stops being optimism and becomes a lie — a play button that cannot be pressed
     * because the UI believes something is already playing.
     */
    @Volatile private var startingPlayback = false
    private var startPromiseJob: kotlinx.coroutines.Job? = null

    val nowPlaying: StateFlow<NowPlaying?> get() = stateHolder.nowPlaying
    val pendingJump get() = stateHolder.pendingJump
    val rewindNotice get() = stateHolder.rewindNotice
    val continuation get() = stateHolder.continuation

    fun dismissRewindNotice() = stateHolder.clearRewindNotice()

    fun dismissContinuationNotice() = stateHolder.clearContinuationNotice()

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
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    // The player has caught up with the promise, or has stopped without
                    // ever keeping it. Either way it is now the authority.
                    if (isPlaying) clearStartPromise()
                    pushState()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                        clearStartPromise()
                    }
                    pushState()
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    clearStartPromise()
                    _state.value = _state.value.copy(error = error.errorCodeName)
                    pushState()
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
        // While a seek is settling, trust the optimistic target over the player.
        val effectivePosition = pendingSeekTargetSec ?: positionSec
        val chapters = context?.chapters.orEmpty()
        val chapter = Chapters.at(chapters, effectivePosition)
        _state.value = _state.value.copy(
            isPlaying = player.isPlaying || startingPlayback,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionSec = effectivePosition,
            durationSec = context?.durationSec ?: (player.duration.takeIf { it > 0 }?.div(1000.0) ?: 0.0),
            speed = player.playbackParameters.speed,
            chapter = chapter,
            chapterIndex = Chapters.indexAt(chapters, effectivePosition),
            chapterCount = chapters.size,
            chapterPositionSec = Chapters.offsetInChapter(chapters, effectivePosition),
            chapterDurationSec = chapter?.let { (it.endSec - it.startSec).coerceAtLeast(0.0) } ?: 0.0,
        )
    }

    /**
     * "Play *the dark forest* on lugu", from Assistant or a car.
     *
     * Answered from the same offline index the search box uses, and it plays the first
     * match rather than opening a list: a spoken request is an instruction, and someone
     * who asked out loud is usually not in a position to read a page of results.
     */
    fun playFromSearch(query: String) {
        scope.launch {
            val match = withContext(Dispatchers.IO) { browseTree.search(query) }.firstOrNull() ?: run {
                _state.value = _state.value.copy(error = "Nothing found for \"$query\"")
                return@launch
            }
            val target = BrowseNode.parse(match.mediaId) as? BrowseNode.Playable ?: return@launch
            play(target.itemId, target.episodeId)
        }
    }

    /**
     * Loads the last thing played into the player, paused, so that it is a press away.
     *
     * Called when the app comes to the foreground. Safe to call as often as that happens: it
     * does nothing at all unless the listener has chosen "Always ready to resume", and the
     * service refuses a second arming while one is in flight or anything is already loaded.
     *
     * **It never starts playing.** The whole of the feature is that the notification and the
     * headset button have something to act on; taking the audio over on being opened is the
     * behaviour this is deliberately not.
     *
     * The setting and the account are read here, before the controller is built, so that on
     * the two settings that do not want this nothing is connected and the playback service is
     * not started at all. Everything after that — whether there is anything to arm, whether
     * something is already loaded, what the last thing played was — is the service's to
     * decide, because the service is the only surface that survives the app being killed.
     */
    fun armLastPlayed() {
        scope.launch {
            val settings = withContext(Dispatchers.IO) { playbackPrefs.settings.first() }
            if (settings.notification != NotificationPersistence.ALWAYS_READY) return@launch
            if (authRepository.account() == null) return@launch
            controller().sendCustomCommand(
                SessionCommand(PersistencePolicy.COMMAND_ARM_LAST_PLAYED, Bundle.EMPTY),
                Bundle.EMPTY,
            )
        }
    }

    /** Starts an item from wherever the sync engine says it should start. */
    fun play(libraryItemId: String, episodeId: String? = null) {
        scope.launch {
            makeStartPromise()
            // Reported here rather than waiting for the next poll: the press that has to
            // be prevented is the one that arrives in the next half second.
            _state.value = _state.value.copy(isPlaying = true, isBuffering = true, error = null)
            val account = authRepository.account() ?: run {
                clearStartPromise()
                pushState()
                return@launch
            }

            val resolved = withContext(Dispatchers.IO) {
                mediaResolver.resolve(account, libraryItemId, episodeId)
            }.getOrElse { failure ->
                clearStartPromise()
                _state.value = _state.value.copy(
                    isBuffering = false,
                    isPlaying = false,
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
            val mediaType = if (episodeId != null) MediaType.PODCAST else MediaType.BOOK
            val speed = withContext(Dispatchers.IO) { playbackPrefs.speedFor(libraryItemId, mediaType) }
            val player = controller()
            player.setMediaItems(resolved.mediaItems, start.trackIndex, start.positionMs)
            player.setPlaybackSpeed(speed)
            player.prepare()
            player.play()
            pushState()
        }
    }

    /**
     * The transport button, which acts on what the listener can see.
     *
     * While the promise is held the button reads Pause, so a press has to mean pause even
     * though the player may not have started yet — anything else would leave the control
     * doing the opposite of what it says.
     */
    fun togglePlayPause() = withController {
        if (it.isPlaying || startingPlayback) {
            clearStartPromise()
            it.pause()
        } else {
            it.play()
        }
    }

    fun pause() = withController {
        clearStartPromise()
        it.pause()
    }

    /**
     * Holds the promise, with a deadline.
     *
     * The deadline is the honest part. A start that never reports anything — a service
     * that failed to come up, a controller that never connected — must not leave the
     * transport claiming to play forever.
     */
    private fun makeStartPromise() {
        startingPlayback = true
        startPromiseJob?.cancel()
        startPromiseJob = scope.launch {
            delay(START_PROMISE_MS)
            if (startingPlayback) {
                startingPlayback = false
                pushState()
            }
        }
    }

    private fun clearStartPromise() {
        startingPlayback = false
        startPromiseJob?.cancel()
        startPromiseJob = null
    }

    /**
     * Relative seek in whole-book seconds.
     *
     * Presses accumulate into a single pending target rather than each reading the
     * player's current position — three quick taps of -10s therefore move exactly 30s,
     * which is the behaviour that makes skip buttons trustworthy. The displayed
     * position updates instantly; the actual seek is issued once presses stop.
     */
    fun seekBy(deltaSec: Double) {
        scope.launch {
            val duration = maxOf(_state.value.durationSec, 0.0)
            val base = pendingSeekTargetSec ?: _state.value.positionSec
            val target = (base + deltaSec).coerceIn(0.0, duration)

            pendingSeekMutex.withLock { pendingSeekTargetSec = target }
            pushState()

            seekJob?.cancel()
            seekJob = scope.launch {
                delay(SEEK_SETTLE_MS)
                commitPendingSeek()
            }
        }
    }

    private suspend fun commitPendingSeek() {
        val target = pendingSeekMutex.withLock { pendingSeekTargetSec } ?: return
        applySeek(target)
        // Clear only after the seek is issued, so state keeps reporting the target
        // rather than briefly snapping back to where the player still is.
        pendingSeekMutex.withLock {
            if (pendingSeekTargetSec == target) pendingSeekTargetSec = null
        }
        pushState()
    }

    private suspend fun applySeek(absoluteSec: Double) {
        val player = controller()
        val context = stateHolder.nowPlaying.value
        if (context == null || context.tracks.size <= 1) {
            player.seekTo((absoluteSec * 1000).toLong())
        } else {
            val position = AbsoluteTiming.toTrack(context.tracks, absoluteSec)
            player.seekTo(position.trackIndex, position.positionMs)
        }
    }

    fun seekTo(absoluteSec: Double) {
        scope.launch {
            seekJob?.cancel()
            pendingSeekMutex.withLock { pendingSeekTargetSec = null }
            applySeek(absoluteSec)
            pushState()
        }
    }

    /** Restarts the current chapter, or steps back one if we only just entered it. */
    fun previousChapter() {
        val chapters = stateHolder.nowPlaying.value?.chapters.orEmpty()
        val target = Chapters.previousChapterStart(chapters, _state.value.positionSec)
            ?: return seekBy(-SKIP_BACK_FALLBACK_SEC)
        seekTo(target)
    }

    fun nextChapter() {
        val chapters = stateHolder.nowPlaying.value?.chapters.orEmpty()
        val target = Chapters.nextChapterStart(chapters, _state.value.positionSec)
            ?: return seekBy(SKIP_FORWARD_FALLBACK_SEC)
        seekTo(target)
    }

    fun hasChapters(): Boolean = (stateHolder.nowPlaying.value?.chapters?.size ?: 0) > 1

    val sleepTimer get() = stateHolder.sleepTimer

    /** Recent large position changes for the loaded item, newest first. */
    fun observePositionHistory(): kotlinx.coroutines.flow.Flow<List<PositionJump>> =
        stateHolder.nowPlaying.flatMapLatest { now ->
            val itemId = now?.libraryItemId
            if (itemId == null) {
                kotlinx.coroutines.flow.flowOf(emptyList())
            } else {
                val account = authRepository.account()
                if (account == null) {
                    kotlinx.coroutines.flow.flowOf(emptyList())
                } else {
                    progressRepository.observeHistory(account, itemId).map { rows ->
                        rows.map { PositionJump(it.fromSec, it.toSec, it.atMs, it.reason) }
                    }
                }
            }
        }

    /** Restores a position from history. An explicit user action, so it may move backwards. */
    fun restorePosition(toSec: Double) {
        scope.launch {
            val account = authRepository.account() ?: return@launch
            val context = stateHolder.nowPlaying.value ?: return@launch
            withContext(Dispatchers.IO) {
                progressRepository.record(
                    account = account,
                    itemId = context.libraryItemId,
                    episodeId = context.episodeId,
                    positionSec = toSec,
                    durationSec = context.durationSec,
                    force = true,
                )
            }
            seekTo(toSec)
            stateHolder.clearJump()
        }
    }

    /** Arms the sleep timer from the current position. Pass null to cancel. */
    fun setSleepTimer(mode: SleepMode?) {
        stateHolder.armSleepTimer(mode, _state.value.positionSec)
        if (mode == null) {
            scope.launch { controller().volume = 1.0f }
        }
    }

    fun extendSleepTimer(byMinutes: Int) {
        val extended = io.github.lightheaded.lugu.core.model.SleepTimer
            .extend(stateHolder.sleepTimer.value.mode, byMinutes)
        stateHolder.armSleepTimer(extended, _state.value.positionSec)
    }

    /**
     * Sets the speed and remembers it for this item; the server has nowhere to store it.
     * For a podcast the key is the podcast itself, not the episode — the narrator is
     * the same next week.
     */
    fun setSpeed(speed: Float) {
        scope.launch {
            val clamped = speed.coerceIn(SpeedSettings.MIN, SpeedSettings.MAX)
            controller().setPlaybackSpeed(clamped)
            stateHolder.nowPlaying.value?.let { now ->
                val mediaType = if (now.episodeId != null) MediaType.PODCAST else MediaType.BOOK
                withContext(Dispatchers.IO) {
                    playbackPrefs.setSpeedFor(now.libraryItemId, mediaType, clamped)
                }
            }
            pushState()
        }
    }

    /** Live player settings, so the UI lays out the controls the listener asked for. */
    val settings: Flow<PlayerSettings> get() = playbackPrefs.settings

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

        /**
         * How long the transport will claim to be playing on the player's behalf.
         *
         * Long enough for a cold service start and a slow first byte over a poor
         * connection, short enough that a start which is never going to happen corrects
         * itself while the listener is still looking at the screen.
         */
        const val START_PROMISE_MS = 20_000L

        /** How long presses are allowed to accumulate before a seek is issued. */
        const val SEEK_SETTLE_MS = 350L

        /** Chapter buttons fall back to a plain skip when an item has no chapters. */
        const val SKIP_BACK_FALLBACK_SEC = 30.0
        const val SKIP_FORWARD_FALLBACK_SEC = 30.0
    }
}
