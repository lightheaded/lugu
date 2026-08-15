package io.github.lightheaded.lugu.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import io.github.lightheaded.lugu.core.download.DownloadCache
import io.github.lightheaded.lugu.core.model.Chapters
import io.github.lightheaded.lugu.core.model.MediaButtonClassifier
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.model.SleepTimer
import io.github.lightheaded.lugu.core.model.SmartRewind
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.PlaybackPrefs
import io.github.lightheaded.lugu.core.sync.PlayerSettings
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import io.github.lightheaded.lugu.core.sync.SessionLedgerRepository
import io.github.lightheaded.lugu.core.sync.SyncScheduler
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one playback brain.
 *
 * A single [MediaLibraryService] serves the app UI, the notification, and later Android
 * Auto, Wear and widgets. Everything about how audio is fetched, sought and recorded
 * lives here rather than in the UI, which is what makes "never stream what is
 * downloaded" and "resume at the right position" true for every surface at once.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class LuguPlaybackService : MediaLibraryService() {

    @Inject lateinit var downloadCache: DownloadCache

    @Inject lateinit var progressRepository: ProgressRepository

    @Inject lateinit var sessionLedgerRepository: SessionLedgerRepository

    @Inject lateinit var authRepository: AuthRepository

    @Inject lateinit var stateHolder: PlaybackStateHolder

    @Inject lateinit var resumptionResolver: ResumptionResolver

    @Inject lateinit var continuationResolver: ContinuationResolver

    @Inject lateinit var playbackPrefs: PlaybackPrefs

    @Inject lateinit var browseTree: BrowseTree

    /** Latest settings, kept current so the player can read them synchronously. */
    @Volatile private var currentSettings: PlayerSettings = PlayerSettings()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer

    private var lastPersistedSec = -1.0
    private var lastTickWallClockMs = 0L

    /**
     * Wall clock at which playback last paused, used to size the smart rewind. Kept
     * here rather than in the classifier because a pause can also come from audio
     * focus loss or a Bluetooth disconnect, not just a button.
     */
    private var pausedAtWallClockMs: Long? = null
    private val buttonClassifier = MediaButtonClassifier()

    override fun onCreate() {
        super.onCreate()

        // Reads through the download cache first and the network second, so a downloaded
        // book plays from disk on every surface without any of them having to know that
        // downloads exist. Auth headers rather than `?token=` URLs on the way out: a
        // signed URL expires mid-book, a header is re-resolved per request.
        val dataSourceFactory = downloadCache.playbackDataSourceFactory()

        player = ExoPlayer.Builder(this)
            .setRenderersFactory(DefaultRenderersFactory(this))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)

            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                // Sample-accurate seeking. The default snaps to the nearest sync point,
                // which is how "skip back 10s" quietly becomes 8 or 13 seconds.
                setSeekParameters(SeekParameters.EXACT)
            }

        player.addListener(PersistenceListener())

        // The session sees a chapter-aware wrapper: notification and lock-screen
        // buttons must never be able to seek a book back to zero.
        val sessionPlayer = ChapterAwarePlayer(player, stateHolder) { currentSettings }

        session = MediaLibrarySession.Builder(this, sessionPlayer, LibrarySessionCallback())
            .setSessionActivity(openAppIntent())
            .build()

        // Settings are read live, so changing a skip duration or hiding a button takes
        // effect immediately rather than at the next playback session.
        scope.launch {
            playbackPrefs.settings.collect { currentSettings = it }
        }

        startPositionTicker()
    }

    /**
     * Chapter navigation for a surface that has no idea what a chapter is.
     *
     * Falls back to the configured skip when the book has none, so the button always
     * does something rather than appearing broken on an unchaptered recording.
     */
    private fun seekChapter(forward: Boolean) {
        val context = stateHolder.nowPlaying.value ?: return
        val position = currentAbsoluteSec() ?: return
        val target = if (forward) {
            Chapters.nextChapterStart(context.chapters, position)
                ?: (position + currentSettings.skipForwardSec)
        } else {
            Chapters.previousChapterStart(context.chapters, position)
                ?: (position - currentSettings.skipBackSec)
        }
        val destination = AbsoluteTiming.toTrack(context.tracks, target.coerceAtLeast(0.0))
        player.seekTo(destination.trackIndex, destination.positionMs)
    }

    /**
     * Steps through the speed presets, wrapping at the end.
     *
     * A car cannot show a slider, and cycling is the one gesture that works with a
     * glance: press until it sounds right. The presets are the listener's own.
     */
    private fun cycleSpeed() {
        val presets = currentSettings.speed.presets.sorted().ifEmpty { return }
        val current = player.playbackParameters.speed
        val next = presets.firstOrNull { it > current + SPEED_EPSILON } ?: presets.first()
        player.setPlaybackSpeed(next)

        // Remembered like any other speed change, so a change made in the car is still
        // in force on the phone.
        val context = stateHolder.nowPlaying.value ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                playbackPrefs.setSpeedFor(
                    context.libraryItemId,
                    if (context.episodeId != null) MediaType.PODCAST else MediaType.BOOK,
                    next,
                )
            }
        }
    }

    private fun openAppIntent(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        return PendingIntent.getActivity(
            this,
            0,
            launch ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /**
     * Position is persisted on a 5 s tick while playing, and on every pause, seek and
     * track change through [PersistenceListener]. The notification's own state is not
     * a record of anything — Room is.
     */
    private fun startPositionTicker() {
        scope.launch {
            while (true) {
                delay(TICK_MS)
                if (player.isPlaying) persistPosition(reason = "tick")
            }
        }
        // The sleep timer ticks faster than the persistence loop because it has to fade
        // smoothly. It lives here, app-side, rather than inside the audio pipeline, so
        // it behaves identically when playback moves to a Cast device later.
        scope.launch {
            while (true) {
                delay(SLEEP_TICK_MS)
                evaluateSleepTimer()
            }
        }
    }

    private fun evaluateSleepTimer() {
        val mode = stateHolder.sleepTimer.value.mode ?: return
        if (!player.isPlaying) return

        val context = stateHolder.nowPlaying.value ?: return
        val position = currentAbsoluteSec() ?: return

        val remaining = SleepTimer.remainingSec(
            mode = mode,
            chapters = context.chapters,
            positionSec = position,
            armedAtPositionSec = stateHolder.sleepArmedAtPositionSec,
            speed = player.playbackParameters.speed,
        )

        val volume = SleepTimer.fadeVolume(remaining)
        stateHolder.updateSleepTimer(remaining, isFading = volume < 1.0f)
        player.volume = volume

        if (remaining != null && remaining <= 0.0) {
            player.pause()
            // Restore the volume so the next play is not silent — the commonest way a
            // fade-out implementation leaves the app apparently broken.
            player.volume = 1.0f
            stateHolder.clearSleepTimer()
            persistPosition(reason = "sleep-timer")
        }
    }

    private fun currentAbsoluteSec(): Double? {
        val context = stateHolder.nowPlaying.value ?: return null
        return AbsoluteTiming.toAbsoluteSec(
            context.tracks,
            player.currentMediaItemIndex,
            player.currentPosition.coerceAtLeast(0),
        )
    }

    private fun persistPosition(reason: String) {
        val context = stateHolder.nowPlaying.value ?: return
        val absolute = currentAbsoluteSec() ?: return
        if (absolute < 0) return
        // A tick that has not moved is not worth a database write.
        if (reason == "tick" && kotlin.math.abs(absolute - lastPersistedSec) < 0.5) return

        val now = System.currentTimeMillis()
        val listenedSec = if (lastTickWallClockMs > 0) (now - lastTickWallClockMs) / 1000.0 else 0.0
        lastTickWallClockMs = now
        lastPersistedSec = absolute

        scope.launch {
            val account = authRepository.account() ?: return@launch
            withContext(Dispatchers.IO) {
                progressRepository.record(
                    account = account,
                    itemId = context.libraryItemId,
                    episodeId = context.episodeId,
                    positionSec = absolute,
                    durationSec = context.durationSec,
                    isFinished = absolute >= context.durationSec - FINISHED_TAIL_SEC &&
                        context.durationSec > 0,
                )
                sessionLedgerRepository.update(
                    id = context.ledgerId,
                    currentTimeSec = absolute,
                    listenedDeltaSec = if (reason == "tick") listenedSec else 0.0,
                )
            }
        }
    }

    /**
     * Applies the smart rewind, once, at the moment playback resumes.
     *
     * Doing it here rather than at pause time is the whole point: rewinding on pause
     * makes the stored position drift backwards on every pause, which is how a book
     * ends up minutes out of place (app #1147, #622). Computed from the real pause
     * duration, so a headset stutter moves nothing.
     */
    private fun applySmartRewindOnResume() {
        val pausedAt = pausedAtWallClockMs ?: return
        pausedAtWallClockMs = null

        val pausedForMs = System.currentTimeMillis() - pausedAt
        val rewindSec = SmartRewind.rewindSeconds(pausedForMs)
        if (rewindSec <= 0.0) return

        val context = stateHolder.nowPlaying.value ?: return
        val absolute = currentAbsoluteSec() ?: return
        val target = (absolute - rewindSec).coerceAtLeast(0.0)

        val position = AbsoluteTiming.toTrack(context.tracks, target)
        player.seekTo(position.trackIndex, position.positionMs)
        // Announce it: an automatic correction the listener cannot see is a bug to them.
        stateHolder.setRewindNotice(SmartRewind.describe(pausedForMs))
    }

    private inner class PersistenceListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val now = System.currentTimeMillis()
            buttonClassifier.onPlaybackStateChanged(isPlaying, now)
            if (!isPlaying) {
                lastTickWallClockMs = 0L
                pausedAtWallClockMs = now
                persistPosition(reason = "pause")
                SyncScheduler.flushNow(this@LuguPlaybackService)
            } else {
                applySmartRewindOnResume()
                lastTickWallClockMs = System.currentTimeMillis()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return

            // Record before persisting, so the position we are leaving is recoverable
            // even if the seek came from a notification button we do not control.
            val context = stateHolder.nowPlaying.value
            if (context != null) {
                val from = AbsoluteTiming.toAbsoluteSec(
                    context.tracks,
                    oldPosition.mediaItemIndex,
                    oldPosition.positionMs.coerceAtLeast(0),
                )
                val to = AbsoluteTiming.toAbsoluteSec(
                    context.tracks,
                    newPosition.mediaItemIndex,
                    newPosition.positionMs.coerceAtLeast(0),
                )
                scope.launch {
                    val account = authRepository.account() ?: return@launch
                    withContext(Dispatchers.IO) {
                        progressRepository.recordJump(
                            account = account,
                            itemId = context.libraryItemId,
                            episodeId = context.episodeId,
                            fromSec = from,
                            toSec = to,
                            reason = "seek",
                        )
                    }
                    if (kotlin.math.abs(to - from) >= UNDO_PROMPT_SEC) {
                        stateHolder.setUndoableJump(
                            io.github.lightheaded.lugu.core.sync.ProgressJump(
                                libraryItemId = context.libraryItemId,
                                episodeId = context.episodeId,
                                fromSec = from,
                                toSec = to,
                            ),
                        )
                    }
                }
            }
            persistPosition(reason = "seek")
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            persistPosition(reason = "transition")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            persistPosition(reason = "ended")
            continueToNext()
        }
    }

    /**
     * The end of a book, and the moment a queue is for.
     *
     * Done here rather than in the UI because this is exactly when the UI is least
     * likely to exist: a book finishing in a car, or with the screen off. The resolver
     * decides *what* comes next and whether it may start itself; this only loads it.
     */
    private fun continueToNext() {
        val finished = stateHolder.nowPlaying.value ?: return
        scope.launch {
            val continuation = withContext(Dispatchers.IO) {
                continuationResolver.resolveNext(finished.libraryItemId, finished.episodeId)
            } ?: return@launch

            stateHolder.set(continuation.resumption.nowPlaying)
            player.setMediaItems(
                continuation.resumption.mediaItems,
                continuation.resumption.startTrackIndex,
                continuation.resumption.startPositionMs,
            )
            player.prepare()
            // Cueing without playing is the whole of the "ask first" setting: the next
            // book is loaded and one press away, and nothing started on its own.
            if (continuation.autoStart) player.play()
        }
    }

    /**
     * Chapter navigation and speed, as buttons a car can show.
     *
     * A car's own transport has no concept of a chapter and no speed control, and both
     * are what an audiobook listener actually reaches for. They are session commands
     * rather than player commands because neither maps onto anything Media3 defines.
     */
    private val carCommands = listOf(
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setSessionCommand(SessionCommand(COMMAND_CHAPTER_PREVIOUS, android.os.Bundle.EMPTY))
            .setDisplayName("Previous chapter")
            .build(),
        CommandButton.Builder(CommandButton.ICON_NEXT)
            .setSessionCommand(SessionCommand(COMMAND_CHAPTER_NEXT, android.os.Bundle.EMPTY))
            .setDisplayName("Next chapter")
            .build(),
        CommandButton.Builder(CommandButton.ICON_PLAYBACK_SPEED)
            .setSessionCommand(SessionCommand(COMMAND_SPEED_CYCLE, android.os.Bundle.EMPTY))
            .setDisplayName("Speed")
            .build(),
    )

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            carCommands.forEach { button -> button.sessionCommand?.let { commands.add(it) } }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands.build())
                .setCustomLayout(carCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                COMMAND_CHAPTER_PREVIOUS -> seekChapter(forward = false)
                COMMAND_CHAPTER_NEXT -> seekChapter(forward = true)
                COMMAND_SPEED_CYCLE -> cycleSpeed()
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(browseTree.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val children = withContext(Dispatchers.IO) { browseTree.children(parentId) }
            LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val item = withContext(Dispatchers.IO) { browseTree.item(mediaId) }
            if (item == null) {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            } else {
                LibraryResult.ofItem(item, null)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val results = withContext(Dispatchers.IO) { browseTree.search(query) }
            session.notifySearchResultChanged(browser, query, results.size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val results = withContext(Dispatchers.IO) { browseTree.search(query) }
            LibraryResult.ofItemList(ImmutableList.copyOf(results), params)
        }

        /**
         * A car hands back an id, or a spoken phrase, and expects playback.
         *
         * Neither carries anything else — no URLs, no track list, no position — so the
         * whole session is resolved here, exactly as it would be from the phone. That is
         * what makes a downloaded book start instantly in a car with no signal.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val requested = mediaItems.firstOrNull()
                ?: return@future MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)

            val target = BrowseNode.parse(requested.mediaId) as? BrowseNode.Playable
                ?: withContext(Dispatchers.IO) {
                    requested.requestMetadata.searchQuery?.let { spoken ->
                        browseTree.search(spoken).firstOrNull()
                            ?.let { BrowseNode.parse(it.mediaId) as? BrowseNode.Playable }
                    }
                }
                // Already-resolved items (the app's own play path) pass through untouched.
                ?: return@future MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)

            val resolved = withContext(Dispatchers.IO) {
                resumptionResolver.resolve(target.itemId, target.episodeId)
            } ?: return@future MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)

            stateHolder.set(resolved.nowPlaying)
            MediaSession.MediaItemsWithStartPosition(
                resolved.mediaItems,
                resolved.startTrackIndex,
                resolved.startPositionMs,
            )
        }

        /**
         * The headset play button after the app was killed, or after a reboot.
         *
         * This is the failure mode the official app is most complained about (app
         * #1800, #41, #578): resumption has to work with no UI alive, which means
         * rebuilding the playlist from Room and a fresh play session here.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val resolved = resumptionResolver.resolveLastPlayed()
                ?: return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)

            stateHolder.set(resolved.nowPlaying)
            MediaSession.MediaItemsWithStartPosition(
                resolved.mediaItems,
                resolved.startTrackIndex,
                resolved.startPositionMs,
            )
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while paused should not silently lose the position.
        persistPosition(reason = "task-removed")
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        persistPosition(reason = "destroy")
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TICK_MS = 5_000L
        const val SLEEP_TICK_MS = 500L

        /** Within this much of the end counts as finished. */
        const val FINISHED_TAIL_SEC = 20.0

        /** A jump at least this large offers an undo, because it may not have been meant. */
        const val UNDO_PROMPT_SEC = 120.0

        /** Float comparison slack, so cycling never sticks on the preset it is already at. */
        const val SPEED_EPSILON = 0.001f

        const val COMMAND_CHAPTER_PREVIOUS = "io.github.lightheaded.lugu.CHAPTER_PREVIOUS"
        const val COMMAND_CHAPTER_NEXT = "io.github.lightheaded.lugu.CHAPTER_NEXT"
        const val COMMAND_SPEED_CYCLE = "io.github.lightheaded.lugu.SPEED_CYCLE"
    }
}

/** Injected so [LuguPlaybackService] does not need to know how resumption is worked out. */
interface ResumptionResolver {
    suspend fun resolveLastPlayed(): Resumption?

    /** Everything needed to play one item, given nothing but its id. */
    suspend fun resolve(itemId: String, episodeId: String?): Resumption?
}

data class Resumption(
    val mediaItems: List<MediaItem>,
    val startTrackIndex: Int,
    val startPositionMs: Long,
    val nowPlaying: NowPlaying,
)

/** Used by callers that already know which account they act for. */
internal typealias AccountProvider = suspend () -> ActiveAccount?
