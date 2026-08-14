package io.github.lightheaded.lugu.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
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
import okhttp3.OkHttpClient

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

    @Inject lateinit var okHttpClient: OkHttpClient

    @Inject lateinit var progressRepository: ProgressRepository

    @Inject lateinit var sessionLedgerRepository: SessionLedgerRepository

    @Inject lateinit var authRepository: AuthRepository

    @Inject lateinit var stateHolder: PlaybackStateHolder

    @Inject lateinit var resumptionResolver: ResumptionResolver

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer

    private var lastPersistedSec = -1.0
    private var lastTickWallClockMs = 0L

    override fun onCreate() {
        super.onCreate()

        val dataSourceFactory = DefaultDataSource.Factory(
            this,
            // Auth headers rather than `?token=` URLs: a signed URL expires mid-book,
            // a header is re-resolved per request by the shared interceptor.
            OkHttpDataSource.Factory(okHttpClient),
        )

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

        session = MediaLibrarySession.Builder(this, player, LibrarySessionCallback())
            .setSessionActivity(openAppIntent())
            .build()

        startPositionTicker()
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

    private inner class PersistenceListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                lastTickWallClockMs = 0L
                persistPosition(reason = "pause")
                SyncScheduler.flushNow(this@LuguPlaybackService)
            } else {
                lastTickWallClockMs = System.currentTimeMillis()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) persistPosition(reason = "seek")
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            persistPosition(reason = "transition")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) persistPosition(reason = "ended")
        }
    }

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult = MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> =
            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))

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

        /** Within this much of the end counts as finished. */
        const val FINISHED_TAIL_SEC = 20.0
    }
}

/** Injected so [LuguPlaybackService] does not need to know how resumption is worked out. */
interface ResumptionResolver {
    suspend fun resolveLastPlayed(): Resumption?
}

data class Resumption(
    val mediaItems: List<MediaItem>,
    val startTrackIndex: Int,
    val startPositionMs: Long,
    val nowPlaying: NowPlaying,
)

/** Used by callers that already know which account they act for. */
internal typealias AccountProvider = suspend () -> ActiveAccount?
