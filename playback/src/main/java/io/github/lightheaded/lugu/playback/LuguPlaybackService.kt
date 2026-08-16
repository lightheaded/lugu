package io.github.lightheaded.lugu.playback

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import io.github.lightheaded.lugu.core.download.DownloadCache
import io.github.lightheaded.lugu.core.model.AutoPlay
import io.github.lightheaded.lugu.core.model.AutoPlayConditions
import io.github.lightheaded.lugu.core.model.AutoPlayOutcome
import io.github.lightheaded.lugu.core.model.Chapters
import io.github.lightheaded.lugu.core.model.MediaButton
import io.github.lightheaded.lugu.core.model.MediaButtonClassifier
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.model.SleepMode
import io.github.lightheaded.lugu.core.model.SleepTimer
import io.github.lightheaded.lugu.core.model.SmartRewind
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.HeadsetAction
import io.github.lightheaded.lugu.core.sync.PlaybackDiary
import io.github.lightheaded.lugu.core.sync.PlaybackEvent
import io.github.lightheaded.lugu.core.sync.PlaybackPrefs
import io.github.lightheaded.lugu.core.sync.PlayerSettings
import io.github.lightheaded.lugu.core.sync.ProgressRepository
import io.github.lightheaded.lugu.core.sync.SessionLedgerRepository
import io.github.lightheaded.lugu.core.sync.StreamSettings
import io.github.lightheaded.lugu.core.sync.SyncScheduler
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    @Inject lateinit var diary: PlaybackDiary

    /** Latest settings, kept current so the player can read them synchronously. */
    @Volatile private var currentSettings: PlayerSettings = PlayerSettings()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer

    private var lastPersistedSec = -1.0
    private var lastTickWallClockMs = 0L

    private val stopAttributor = StopAttributor()
    private val retryPolicy = PlaybackRetryPolicy()
    private val reconnectPolicy = ReconnectPolicy()
    private val skipEnforcer = SkipRegionEnforcer()

    /**
     * The regions to skip in what is playing, or null for a book and for a show with no
     * trim — which is nearly everything.
     *
     * Rebuilt when the item, the show's trim or the announce setting changes, so the
     * half-second tick reads a field rather than recomputing regions from chapters on
     * every pass. Volatile because that tick and the collector that maintains it are on
     * different coroutines.
     */
    @Volatile
    private var skipPlan: SkipPlan? = null

    private var networkWatcher: NetworkWatcher? = null

    /**
     * The failure a returning network is allowed to undo, or null when there is none.
     *
     * Cleared by every transport command and by any successful playback, which is the half of
     * the rule that matters most: a book the listener paused on purpose has nothing recorded
     * against it, so nothing can restart it. See [ReconnectPolicy].
     */
    private var networkStall: NetworkStall? = null

    /**
     * The reason Media3 gave the last time it stopped wanting to play.
     *
     * Held rather than read on demand because the reason is delivered once, with the
     * change, and is gone by the time the stop is classified. It is cleared again on the
     * way back up: a reason that explained an earlier pause must not be left lying around
     * to explain a later stop it had nothing to do with.
     */
    private var lastStopReason = StopAttributor.REASON_UNREPORTED

    private var retryAttempts = 0
    private var retryJob: Job? = null

    /** Throttles the buffering record, which would otherwise crowd out the diary. */
    private var lastBufferingRecordMs = 0L

    private var audioSessionId = C.AUDIO_SESSION_ID_UNSET
    private var loudnessBoost: LoudnessBoost? = null

    private var routeWatcher: AudioRouteWatcher? = null

    /**
     * The route whose disconnection stopped playback, and when.
     *
     * Only a stop caused by a disconnect may be undone by a reconnection. A book the
     * listener paused on purpose must stay paused when they plug their headphones back
     * in, which is the difference between a helpful resume and a startling one.
     */
    private var pausedByRoute: AudioRouteClass? = null
    private var pausedByRouteAtMs = 0L

    private var shakeDetector: ShakeDetector? = null

    /** The sensitivity the accelerometer is currently registered at; null means not registered. */
    private var shakeListeningAt: Int? = null

    /** How far to rewind on the next play, set when the sleep timer stopped playback. */
    private var pendingSleepRewindSec: Double? = null

    /** Whether the timer is being held for a car, so the hold can be taken off again. */
    private var sleepSuspendedForCar = false

    /**
     * The mode last seen armed, so a newly armed timer starts with a clean record.
     *
     * Only used to notice that the timer has been re-armed; the mode itself is the state
     * holder's.
     */
    private var lastSleepMode: SleepMode? = null

    /** Set once a timer has been reported as unable to come due, so it is said once. */
    private var unfireableTimerRecorded = false

    /** What the ducking setting was last applied as; null until the first settings arrive. */
    private var duckingInForce: Boolean? = null

    /** The item and track whose metadata carries [metadataChapterIndex]. */
    private var metadataItemKey: String? = null

    /** The chapter last written into the session metadata, so only real changes are written. */
    private var metadataChapterIndex = -1

    /** Controllers that identify themselves as a car, by package name. */
    private val carControllers = mutableSetOf<String>()

    /**
     * Wall clock at which playback last paused, used to size the smart rewind. Kept
     * here rather than in the classifier because a pause can also come from audio
     * focus loss or a Bluetooth disconnect, not just a button.
     */
    private var pausedAtWallClockMs: Long? = null
    private val buttonClassifier = MediaButtonClassifier()

    /**
     * Whether anything has actually played since this service was created.
     *
     * The foreground hold is spent on a session somebody is listening to, not on one that
     * was merely armed — see [PersistencePolicy.holdsForegroundWhilePaused].
     */
    private var hasPlayedSinceCreate = false

    /** Whether the service is currently being held in the foreground past a pause. */
    private var foregroundHeld = false

    /** The arming in flight, so a second request does not start a second resolution. */
    private var armingJob: Job? = null

    /**
     * The wait between a chosen device connecting and the book starting, or null when there
     * is none in flight.
     *
     * One at a time: connecting a headset produces several events seconds apart, and each of
     * them starts this service again. The second and third find a wait already running and
     * leave it alone rather than restarting the countdown, which would otherwise make the
     * wait longer every time the hardware felt chatty.
     */
    private var autoPlayJob: Job? = null

    /**
     * Whether the waiting notification is the one holding the service in the foreground.
     *
     * Read by [onUpdateNotificationAsync], which is Media3's decision rather than ours: while
     * a start is pending there is nothing playing, so without this the framework would let
     * the foreground go in the middle of the wait and the system would be free to reclaim the
     * process before the book ever started.
     */
    private var autoPlayWaiting = false

    /** What the pending start is for, and how much of its wait is left. */
    private var autoPlayDeviceName = AutoPlay.UNNAMED
    private var autoPlayRemainingSec = 0

    override fun onCreate() {
        super.onCreate()
        diary.record(PlaybackEvent.SERVICE_CREATED)

        // Installed before any session exists, so the first notification is already built
        // from the listener's buttons rather than from Media3's previous/next pair.
        setMediaNotificationProvider(LuguNotificationProvider(this))

        // Read once, synchronously, because two things below are fixed at construction and
        // cannot be changed afterwards: the load control and the retained-stream cache. See
        // [settingsAtStartup] for what that costs and what happens when it times out.
        val startupSettings = settingsAtStartup()
        currentSettings = startupSettings

        // Reads through the download cache first, the retained-stream cache second and the
        // network third, so a downloaded book plays from disk on every surface without any of
        // them having to know that downloads exist. Auth headers rather than `?token=` URLs on
        // the way out: a signed URL expires mid-book, a header is re-resolved per request.
        val dataSourceFactory = downloadCache.playbackDataSourceFactory(
            retainStreamedMb = startupSettings.stream.retainStreamedMb,
        )

        player = ExoPlayer.Builder(this)
            .setRenderersFactory(DefaultRenderersFactory(this))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControlFor(startupSettings.stream))
            // Speech to begin with, which is what tells Media3 to pause rather than duck;
            // [applyDucking] replaces it with whatever the setting says.
            .setAudioAttributes(audioAttributes(duck = false), /* handleAudioFocus = */ true)
            // The starting value only; the route setting takes over as soon as the first
            // settings emission arrives, and pausing on disconnect is its default.
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                // Sample-accurate seeking. The default snaps to the nearest sync point,
                // which is how "skip back 10s" quietly becomes 8 or 13 seconds.
                setSeekParameters(SeekParameters.EXACT)
            }

        player.addListener(PersistenceListener())
        player.addListener(DiagnosticsListener())

        loudnessBoost = LoudnessBoost(diary)
        shakeDetector = ShakeDetector(this) { extendSleepTimerOnShake() }

        // The session sees a chapter-aware wrapper: notification and lock-screen
        // buttons must never be able to seek a book back to zero. The outer wrapper
        // exists only to hear the commands go past, so a stop that nobody asked for can
        // be told apart from one that somebody did.
        val sessionPlayer = TransportAnnouncingPlayer(
            player = ChapterAwarePlayer(player, stateHolder) { currentSettings },
            onPlayRequested = {
                diary.record(PlaybackEvent.PLAY_REQUESTED, playbackDetail())
                pausedByRoute = null
                networkStall = null
            },
            onPauseRequested = {
                // A deliberate pause ends any retry in flight; nothing should start
                // playing again after the listener has said stop. The stall goes with it,
                // for the same reason and with more force: a returning network must never
                // undo a stop the listener asked for, however recent the failure before it.
                retryJob?.cancel()
                pausedByRoute = null
                networkStall = null
            },
            onStopRequested = {
                stopAttributor.declare(StopAttributor.REASON_STOP_COMMAND, System.currentTimeMillis())
                networkStall = null
            },
        )

        session = MediaLibrarySession.Builder(this, sessionPlayer, LibrarySessionCallback())
            .setSessionActivity(openAppIntent())
            /*
             * Stated rather than inherited, because artwork is a `content://` URI now.
             *
             * Covers are served by [CoverProvider] so that a car — which fetches artwork in
             * its own process, with none of lugu's authentication — can show them at all. The
             * notification's own artwork is loaded in *this* process by whatever loader the
             * session holds, and a loader that only speaks http would quietly draw nothing.
             * `DefaultDataSource` handles content, file and http alike, so both ends of the
             * same URI work. Wrapped in the cache Media3 would have used anyway, so a cover
             * is decoded once rather than on every notification update.
             */
            .setBitmapLoader(
                CacheBitmapLoader(
                    DataSourceBitmapLoader(
                        DataSourceBitmapLoader.DEFAULT_EXECUTOR_SERVICE.get(),
                        DefaultDataSource.Factory(this),
                    ),
                ),
            )
            .build()

        // Being refused a foreground service is one of the ways playback simply does not
        // happen, and it is invisible from the outside: no error, no notification, no
        // audio. Recording it is the difference between a diagnosis and a shrug.
        setListener(
            object : MediaSessionService.Listener {
                override fun onForegroundServiceStartNotAllowedException() {
                    diary.record(PlaybackEvent.ERROR, "foreground service start not allowed")
                }
            },
        )

        routeWatcher = AudioRouteWatcher(
            context = this,
            isCarMode = ::isCarMode,
            onLost = ::onAudioRouteLost,
            onGained = ::onAudioRouteGained,
        ).also { it.start() }

        networkWatcher = NetworkWatcher(this, ::onNetworkAvailable).also { it.start() }

        // Settings are read live, so changing a skip duration, hiding a button or turning
        // on silence skipping takes effect immediately rather than at the next session.
        scope.launch {
            playbackPrefs.settings.collect { settings ->
                currentSettings = settings
                applyAudioSettings(settings)
                syncShakeListening()
                // The notification's layout is read once, when a controller connects, so a
                // setting that moves afterwards changes nothing unless it is pushed. The
                // previous attempt at controlling these buttons failed here and nowhere
                // else. Pushed on every emission rather than on a comparison: Media3 only
                // notifies controllers when the list has really changed, so an unnecessary
                // push costs nothing and a missed one costs the whole feature.
                pushNotificationLayout()
                // The persistence setting is read at the moment Media3 asks about the
                // notification, and it may not ask again for ten minutes. Someone who turns
                // persistence on while looking at a paused book expects it to apply to that
                // book, not to the next one.
                triggerNotificationUpdate()
            }
        }

        // Kept in a field rather than read on the tick, because building it means walking
        // an episode's chapters and the tick runs twice a second for the whole of a book.
        scope.launch { skipPlans().collect { skipPlan = it } }

        // The accelerometer follows the timer rather than the app: a sensor registered
        // for the whole life of the service is a battery cost nothing on screen explains.
        scope.launch {
            stateHolder.sleepTimer.collect { timer ->
                // A newly armed timer starts with a clean record: whatever was said about
                // the last one — that it could not come due, that it was held for a car —
                // was about that one.
                if (timer.mode != lastSleepMode) {
                    lastSleepMode = timer.mode
                    unfireableTimerRecorded = false
                    if (timer.mode == null) sleepSuspendedForCar = false
                }
                syncShakeListening()
            }
        }

        startPositionTicker()
    }

    /**
     * The settings, read before anything is built.
     *
     * Almost every setting in lugu is read live, off the flow below, and takes effect on the
     * playing book. Two cannot: a `LoadControl` is handed to `ExoPlayer.Builder` and is fixed
     * for the life of the player, and a `SimpleCache` holds a lock on its folder and an index
     * it has already read. Both therefore need an answer here, in `onCreate`, before the
     * collector has had a chance to emit anything.
     *
     * So this blocks — briefly, and with a bound. The read is one small preferences file and
     * is almost always warm; the timeout exists for the cold start under disk pressure where
     * it is not, and the answer when it expires is the defaults, which are the same defaults
     * the flow would have produced for anyone who has never touched the settings. What is
     * lost in that case is one service lifetime's worth of a non-default buffer size, and
     * everything that *can* be applied live still is, a moment later, when the flow emits.
     *
     * Blocking on `Dispatchers.IO` rather than inline so the disk read does not happen on the
     * main thread — the main thread waits for it, which is the point, but it does not do it.
     */
    private fun settingsAtStartup(): PlayerSettings = runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(SETTINGS_READ_TIMEOUT_MS) { playbackPrefs.currentSettings() }
    } ?: PlayerSettings()

    /**
     * How far ahead the player reads, and the ceiling that stops that being a crash.
     *
     * The numbers, and the bitrate assumption behind the byte ceiling, are worked out and
     * argued in [StreamBuffer]. Two things about how they are applied belong here:
     *
     * **Streaming, not local.** Media3 1.11 keeps separate buffer durations for the two and
     * decides which apply from the media item's URI scheme. Everything lugu plays reads as
     * streaming, including a fully downloaded book, because a downloaded item keeps its
     * server URL as its URI and finds its bytes by cache key instead — see `MediaResolver`.
     * So the deep buffer applies to downloads too, which costs nothing: the reads come off
     * the disk and complete instantly. The streaming-specific setter is used rather than the
     * generic one anyway, so that the day the download path moves to `file:` URIs, the local
     * defaults are already sensible and this needs no thought.
     *
     * **`prioritizeTimeOverSizeThresholds` is left false for streaming**, which is what makes
     * the byte ceiling authoritative rather than advisory: `DefaultLoadControl` stops loading
     * on the ceiling even when the buffered duration is still short of the minimum. True
     * would invert that and let a high-bitrate file spend whatever the minutes worked out to.
     *
     * A change to the buffer setting takes effect **the next time the playback service
     * starts**, and there is no way to make it otherwise — the load control is not
     * replaceable on a built player. Nothing here pretends to apply it live.
     */
    private fun loadControlFor(stream: StreamSettings): DefaultLoadControl {
        val plan = StreamBuffer.planFor(
            bufferAheadMinutes = stream.bufferAheadMinutes,
            maxHeapBytes = Runtime.getRuntime().maxMemory(),
        )
        return DefaultLoadControl.Builder()
            .setBufferDurationsMsForStreaming(
                plan.minBufferMs,
                plan.maxBufferMs,
                StreamBuffer.BUFFER_FOR_PLAYBACK_MS,
                StreamBuffer.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setTargetBufferBytes(plan.targetBufferBytes)
            .build()
    }

    /**
     * A network arrived, and something may be waiting for it.
     *
     * Whether anything should happen is [ReconnectPolicy]'s decision and not this method's;
     * every outcome is written to the diary, including the refusals, because "it did not come
     * back on its own" and "it came back on its own when it should not have" are both
     * complaints that cannot be investigated from silence.
     *
     * The retry ladder is reset as well as cancelled. A ladder exhausted against the old
     * connection has no bearing on the new one, and leaving the count where it was would mean
     * the first failure after a genuine reconnection got no attempts at all.
     */
    private fun onNetworkAvailable() {
        val verdict = reconnectPolicy.verdictFor(
            stall = networkStall,
            nowMs = System.currentTimeMillis(),
            hasSomethingLoaded = player.mediaItemCount > 0,
            alreadyPlaying = player.isPlaying,
        )
        diary.record(NETWORK_RETURNED, playbackDetail(verdict.reason))
        if (verdict.action != ReconnectAction.RESUME) return

        networkStall = null
        retryJob?.cancel()
        retryAttempts = 0
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    /**
     * Applies the settings that change what the audio itself sounds like.
     *
     * Called on every settings emission rather than only at startup, because all three
     * are things a listener changes *while* something is playing — the point of the
     * silence-skipping switch is to hear the difference on the recording in front of you.
     */
    private fun applyAudioSettings(settings: PlayerSettings) {
        player.skipSilenceEnabled = settings.audio.skipSilence
        player.setHandleAudioBecomingNoisy(settings.route.pauseOnDisconnect)
        loudnessBoost?.apply(audioSessionId, settings.audio.volumeBoostDb)
        applyDucking(settings.audio.duckOnInterruption)
    }

    /**
     * Whether a short interruption lowers the book or stops it.
     *
     * Media3 offers no switch for this. What its focus manager reads is the *content type*
     * of the audio attributes, and it pauses rather than ducks for exactly one of them:
     * speech. It also passes that decision on to the platform, which then sends a full
     * transient loss instead of a duck request and does no ducking of its own. lugu declared
     * speech from the first line it ever ran, so the behaviour up to now was to pause for
     * every navigation prompt, and no setting could have changed it.
     *
     * So the setting is honoured by choosing the content type. Nothing else about the
     * attributes moves — the usage stays media, which is what decides which volume slider
     * applies and which kind of focus is requested. What is given up when ducking is on is
     * the platform's knowledge that this is speech, which some devices use for
     * post-processing; that is the price of the interruption being audible over the book
     * rather than replacing it.
     */
    private fun applyDucking(duck: Boolean) {
        if (duckingInForce == duck) return
        duckingInForce = duck
        player.setAudioAttributes(audioAttributes(duck), /* handleAudioFocus = */ true)
        // Written down because a duck itself produces no callback at all: the record of why
        // the book went quiet for a moment is this line and the absence of a suppression.
        diary.record(
            AUDIO_FOCUS,
            if (duck) "a short interruption will lower the book" else "a short interruption will pause it",
        )
    }

    private fun audioAttributes(duck: Boolean) = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(if (duck) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_SPEECH)
        .build()

    /**
     * Whether this phone is currently in a car.
     *
     * Two independent signals, either of which is enough: a controller that identifies
     * itself as a car projection, and the system's own car UI mode. Neither needs a
     * permission, which is the reason for taking this route rather than reading the
     * Bluetooth device class — see [AudioRoutes].
     */
    private fun isCarMode(): Boolean {
        if (carControllers.isNotEmpty()) return true
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_CAR
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
        seekToAbsolute(target)
    }

    /**
     * Seeks to a whole-book position, which is the only kind of position anything outside
     * ExoPlayer deals in.
     *
     * The single-track case is separated out rather than left to [AbsoluteTiming.toTrack],
     * which answers with track 0 offset 0 when it has no track list to work from. A seek to
     * zero on a forty-hour book is the fault all of this exists to prevent, and it must not
     * be reachable through a rounding case.
     */
    private fun seekToAbsolute(absoluteSec: Double) {
        val tracks = stateHolder.nowPlaying.value?.tracks.orEmpty()
        val safe = absoluteSec.coerceAtLeast(0.0)
        if (tracks.size <= 1) {
            player.seekTo((safe * 1000).toLong())
        } else {
            val destination = AbsoluteTiming.toTrack(tracks, safe)
            player.seekTo(destination.trackIndex, destination.positionMs)
        }
    }

    /** Moves by a signed number of seconds from wherever the book currently is. */
    private fun seekRelative(deltaSec: Double) {
        val position = currentAbsoluteSec() ?: return
        seekToAbsolute(position + deltaSec)
    }

    /**
     * Does what the listener asked a headset's side button to do.
     *
     * The classifier decides whether the press was real; it is what removes the phantom
     * rewinds that some headsets cause by emitting a pause and a play in the same
     * millisecond, and a press it rejects must not act. What a real press *means* is a
     * setting, and is resolved by [HeadsetActions].
     */
    private fun applyHeadsetPress(button: MediaButton) {
        val classified = buttonClassifier.classify(button, System.currentTimeMillis(), player.isPlaying)
        val press = HeadsetActions.resolve(classified, currentSettings.headset) ?: run {
            // Worth a line: "the headphone button did nothing" is a complaint that cannot
            // be told from a broken headset without knowing the press was seen and dropped.
            diary.record(HEADSET_BUTTON, "${button.name.lowercase()} dropped as $classified")
            return
        }
        val forward = press.direction == HeadsetDirection.NEXT

        when (press.action) {
            HeadsetAction.SKIP -> seekRelative(
                if (forward) {
                    currentSettings.skipForwardSec.toDouble()
                } else {
                    -currentSettings.skipBackSec.toDouble()
                },
            )

            HeadsetAction.CHAPTER -> seekChapter(forward)

            // Never `seekToPrevious()`: on a single-file book that seeks to zero, which is
            // the incident all of this was written for. The queue only runs forwards, so
            // there is genuinely nothing before the current item to go to.
            HeadsetAction.ITEM -> if (forward) {
                persistPosition(reason = "headset-next-item")
                continueToNext(startedByListener = true)
            } else {
                diary.record(HEADSET_BUTTON, playbackDetail("previous item, but the queue has none"))
            }

            HeadsetAction.NOTHING -> diary.record(
                HEADSET_BUTTON,
                "${button.name.lowercase()} ignored, as configured",
            )
        }
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
     * A chosen device connected, or the listener said not now.
     *
     * Media3 owns every other reason this service is started — media buttons, controllers
     * binding — so anything not ours goes straight to it untouched.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        when (intent?.action) {
            AutoPlayTrigger.ACTION_AUTO_PLAY -> {
                beginAutoPlay(
                    deviceName = intent.getStringExtra(AutoPlayTrigger.EXTRA_DEVICE_NAME)
                        ?.takeIf { it.isNotBlank() } ?: AutoPlay.UNNAMED,
                    waitSec = intent.getIntExtra(
                        AutoPlayTrigger.EXTRA_WAIT_SEC,
                        AutoPlay.DEFAULT_WAIT_SEC,
                    ),
                )
                START_NOT_STICKY
            }

            AutoPlayTrigger.ACTION_CANCEL_AUTO_PLAY -> {
                cancelAutoPlay()
                START_NOT_STICKY
            }

            else -> super.onStartCommand(intent, flags, startId)
        }

    /**
     * Waits, then starts the last thing played.
     *
     * The foreground notification goes up first and before anything else can fail, because
     * the service was started with `startForegroundService` and the system gives only a few
     * seconds to make good on that — including on the paths that end in refusing. Everything
     * afterwards is free to take as long as it takes or to decide against playing at all.
     *
     * Resolving what to play happens during the wait; loading it into the player does not.
     * That split is deliberate: the slow part — reading the last position, asking the server
     * for a play session — is overlapped with the wait, while the part that would make Media3
     * post a notification of its own is left until the moment of playing, so there is one
     * notification on screen throughout rather than two arguing about it.
     *
     * The wait itself is two things in order. First the audio actually moving to the device,
     * which is watched for rather than guessed at — see [awaitAudioSwitchover]. Then the
     * listener's own extra seconds on top, which default to one and can be none.
     */
    private fun beginAutoPlay(deviceName: String, waitSec: Int) {
        autoPlayWaiting = true

        // A second event from the same connection — the link, then the audio profile, then
        // sometimes the call profile, seconds apart. Each of them starts this service again
        // and each has to be answered with a foreground notification, but the wait already
        // running is the right one: restarting it would push the start further away every
        // time the hardware felt talkative.
        if (autoPlayJob?.isActive == true) {
            postWaitingNotification(autoPlayDeviceName, autoPlayRemainingSec)
            return
        }

        autoPlayDeviceName = deviceName
        autoPlayRemainingSec = waitSec.coerceIn(0, AutoPlay.MAX_WAIT_SEC)
        postWaitingNotification(deviceName, autoPlayRemainingSec)

        autoPlayJob = scope.launch {
            val resolving = async { resolveForAutoPlay() }
            val switched = awaitAudioSwitchover(deviceName)
            if (switched) {
                while (autoPlayRemainingSec > 0) {
                    delay(1_000)
                    autoPlayRemainingSec--
                    if (autoPlayRemainingSec > 0) {
                        postWaitingNotification(deviceName, autoPlayRemainingSec)
                    }
                }
            }
            finishAutoPlay(deviceName, resolving.await(), switched)
        }
    }

    /**
     * Waits for the audio to have somewhere worth going.
     *
     * This is the part the listener used to have to guess at with a stopwatch. A device
     * announces its connection before the audio route has moved, and anything played inside
     * that gap comes out of the phone's speaker — so rather than picking a number large
     * enough to cover it, the arrival of an output is watched for directly. On a fast headset
     * that is sooner than any delay anybody would have configured; on a slow one it is later,
     * and correct.
     *
     * `registerAudioDeviceCallback` is the same permission-free path [AudioRouteWatcher] uses,
     * and it delivers the devices that are already present as its first callback — so a device
     * that routed before this ran is not waited for. The explicit check first makes that
     * obvious rather than relying on it.
     *
     * Returns false if nothing arrived within [ROUTE_WAIT_TIMEOUT_MS], which is a real
     * outcome rather than an error: plenty of Bluetooth devices are not audio devices, and a
     * watch connecting should end here rather than start a book.
     */
    private suspend fun awaitAudioSwitchover(deviceName: String): Boolean {
        if (hasListenableOutput()) return true

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            // No audio manager is not a reason to refuse: nothing can be observed, so the
            // listener's own wait is all there is, exactly as it was before this existed.
            ?: return true

        postWaitingNotification(deviceName, autoPlayRemainingSec, waitingForAudio = true)

        val arrived = withTimeoutOrNull(ROUTE_WAIT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val handler = Handler(Looper.getMainLooper())
                val callback = object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                        val carMode = isCarMode()
                        val listenable = addedDevices.orEmpty().any {
                            it.isSink && AudioRoutes.classify(it.type, carMode) != AudioRouteClass.OTHER
                        }
                        if (listenable && continuation.isActive) {
                            audioManager.unregisterAudioDeviceCallback(this)
                            continuation.resume(true)
                        }
                    }
                }
                continuation.invokeOnCancellation {
                    audioManager.unregisterAudioDeviceCallback(callback)
                }
                audioManager.registerAudioDeviceCallback(callback, handler)
            }
        } == true

        if (arrived) postWaitingNotification(deviceName, autoPlayRemainingSec)
        return arrived
    }

    /**
     * What to play, or null when there is nothing.
     *
     * Nothing is loaded into the player here. A player that already has something in it is
     * left alone and reported as ready: the listener may have armed a book, or a controller
     * may have loaded one while the wait was running, and either is a better answer than
     * whatever was last played.
     */
    private suspend fun resolveForAutoPlay(): Resumption? {
        if (player.mediaItemCount > 0) return null
        return withContext(Dispatchers.IO) {
            runCatching { resumptionResolver.resolveLastPlayed() }.getOrNull()
        }
    }

    /**
     * The wait is over. Everything that could have changed during it is asked again.
     *
     * The checks are [AutoPlay.decide]'s rather than this method's, so the rule about what
     * may interrupt a start is written once and can be read without a device.
     */
    private suspend fun finishAutoPlay(
        deviceName: String,
        resolved: Resumption?,
        audioSwitchedOver: Boolean,
    ) {
        val alreadyLoaded = player.mediaItemCount > 0
        val outcome = AutoPlay.decide(
            AutoPlayConditions(
                audioSwitchedOver = audioSwitchedOver,
                deviceStillConnected = hasListenableOutput(),
                someoneElseHasTheAudio = someoneElseHasTheAudio(),
                onACall = onACall(),
                hasSomethingToPlay = alreadyLoaded || resolved != null,
            ),
        )

        when (outcome) {
            is AutoPlayOutcome.Refuse -> {
                diary.record(
                    AutoPlayTrigger.AUTO_PLAY_REFUSED,
                    "$deviceName, ${outcome.refusal.reason}",
                )
                endAutoPlayWait(handingOver = false)
            }

            AutoPlayOutcome.Start -> {
                if (resolved != null && !alreadyLoaded) {
                    stateHolder.set(resolved.nowPlaying)
                    player.setMediaItems(
                        resolved.mediaItems,
                        resolved.startTrackIndex,
                        resolved.startPositionMs,
                    )
                    applyRememberedSpeed(resolved.nowPlaying)
                }
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
                AutoPlayTrigger.clearCancelled()
                diary.record(AutoPlayTrigger.AUTO_PLAY_STARTED, playbackDetail(deviceName))
                endAutoPlayWait(handingOver = true)
            }
        }
    }

    /**
     * The speed this item was last listened at.
     *
     * A book loaded through `PlaybackConnection.play` gets its speed from the same store, but
     * one loaded straight into the player comes up at whatever speed the player happens to
     * hold, which on a fresh service is 1x. There are three such paths and every one of them
     * belongs to somebody who is not looking at the screen: a device connecting, a headset
     * press through `onPlaybackResumption`, and a car handing back an id through
     * `onSetMediaItems`. All three call this.
     *
     * A book that starts by itself at the wrong speed is a worse first impression than one
     * that does not start at all — and it is worst in the car, where the listener has to
     * find a speed control while driving to undo it.
     *
     * Setting the speed before the items are handed back is deliberate and safe: playback
     * parameters belong to the player rather than to an item, and survive the list being
     * replaced. Doing it afterwards would mean a spoken word or two at the wrong speed.
     */
    private suspend fun applyRememberedSpeed(nowPlaying: NowPlaying) {
        val type = if (nowPlaying.episodeId != null) MediaType.PODCAST else MediaType.BOOK
        val speed = withContext(Dispatchers.IO) {
            runCatching { playbackPrefs.speedFor(nowPlaying.libraryItemId, type) }.getOrNull()
        } ?: return
        player.setPlaybackSpeed(speed)
    }

    /**
     * "Not now", from the notification.
     *
     * The refusal is remembered for a minute, in [AutoPlayTrigger], because the events from
     * one connection arrive over several seconds and the next of them would otherwise start
     * the wait again — which reads, from the outside, as a cancel button that does not work.
     */
    private fun cancelAutoPlay() {
        val wasWaiting = autoPlayJob?.isActive == true || autoPlayWaiting
        autoPlayJob?.cancel()
        autoPlayJob = null
        if (wasWaiting) {
            AutoPlayTrigger.noteCancelled(System.currentTimeMillis())
            diary.record(AutoPlayTrigger.AUTO_PLAY_CANCELLED)
        }
        endAutoPlayWait(handingOver = false)
    }

    /**
     * The end of the wait, whichever way it went.
     *
     * The two ways out want opposite handling, and confusing them is what left the prompt on
     * screen after "Not now".
     *
     * **A book started.** Something is coming to replace this notification, so the foreground
     * changes hands before the waiting notification is removed — removing it first would drop
     * the service out of the foreground for as long as it takes Media3 to notice, and a
     * service that is briefly not foreground is a service the system may reclaim mid-sentence.
     * The removal itself has to wait for Media3 to actually post; see
     * [retireWaitingNotification].
     *
     * **Nothing started**, because it was refused or because the listener said no. Then
     * nothing is coming, and waiting for it would leave a countdown on screen that has already
     * been answered — so the prompt goes immediately. Media3 is still asked to update, because
     * a book may be sitting loaded and paused from earlier and is entitled to its own
     * notification back; and the service only stops if there is nothing left for it to hold.
     */
    private fun endAutoPlayWait(handingOver: Boolean) {
        autoPlayWaiting = false
        if (handingOver) {
            runCatching { triggerNotificationUpdate() }
            scope.launch { retireWaitingNotification() }
            return
        }

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(AUTO_PLAY_NOTIFICATION_ID)
        runCatching { triggerNotificationUpdate() }
        if (player.mediaItemCount == 0 && !player.isPlaying) stopSelf()
    }

    /**
     * Removes the waiting notification once the player's has taken over from it.
     *
     * Cancelling it directly does nothing, which is why it used to sit there under the player
     * for the rest of the session. The waiting notification is what [postWaitingNotification]
     * handed to `startForeground`, and a service's current foreground notification cannot be
     * cancelled — the system keeps it on screen for exactly as long as the service is in the
     * foreground under that id. It only becomes an ordinary, removable notification once
     * `startForeground` has been called again with Media3's id instead.
     *
     * `triggerNotificationUpdate` starts that but does not finish it: the notification is
     * built asynchronously, so the cancel that used to follow it immediately was always racing
     * a post that had not happened yet, and always lost. So this waits for Media3's
     * notification to actually appear before removing this one.
     *
     * The timeout is the safety net rather than the mechanism. If Media3 never posts, the
     * cancel still runs — by then the service is no longer foreground under this id either,
     * because nothing is holding it there, and the notification goes.
     */
    private suspend fun retireWaitingNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        withTimeoutOrNull(HANDOVER_TIMEOUT_MS) {
            while (manager?.activeNotifications?.any { it.id == MEDIA_NOTIFICATION_ID } != true) {
                delay(HANDOVER_POLL_MS)
            }
        }
        NotificationManagerCompat.from(this).cancel(AUTO_PLAY_NOTIFICATION_ID)
    }

    /**
     * The notification shown while a start is pending.
     *
     * It exists for two reasons that are both about honesty. The service has to be in the
     * foreground within seconds of being started and this is what puts it there; and a book
     * that is about to start playing by itself should say so before it does, with a way to
     * stop it, rather than after.
     *
     * `LaunchActivityFromNotification` is suppressed deliberately. Its advice — a tap should
     * open a screen, and only the buttons should reach a service — is right for a notification
     * that represents something ongoing, and wrong for one that asks a single question and
     * lives for a second. Opening lugu is not an answer to "a book is about to start", and the
     * feedback the check exists to protect is present either way: the notification disappears
     * and no book starts.
     */
    @SuppressLint("LaunchActivityFromNotification")
    private fun postWaitingNotification(
        deviceName: String,
        remainingSec: Int,
        waitingForAudio: Boolean = false,
    ) {
        ensureAutoPlayChannel()
        val text = when {
            // Said plainly, because this is the phase with no number attached to it and a
            // notification that only said "starting" would look stuck.
            waitingForAudio -> "Waiting for the audio to switch over"
            remainingSec <= 0 -> "Starting now"
            remainingSec == 1 -> "Starting in a second"
            else -> "Starting in $remainingSec seconds"
        }
        val cancel = PendingIntent.getService(
            this,
            0,
            Intent(this, LuguPlaybackService::class.java)
                .setAction(AutoPlayTrigger.ACTION_CANCEL_AUTO_PLAY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, AUTO_PLAY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_auto_play_notification)
            .setContentTitle("$deviceName connected")
            .setContentText(text)
            /*
             * The whole notification says no, not just the button on it.
             *
             * Tapping a notification usually opens the app, and that is the right default
             * almost everywhere — but this one exists for a second or two and asks exactly
             * one question. Opening lugu is not an answer to it, and hunting for a button
             * inside something that is already under your thumb is how the moment gets
             * missed. The labelled button stays, because a tap target with no label is not
             * an offer anybody can see.
             *
             * Swiping it away means the same thing. Dismissing the prompt and then having
             * the book start anyway would make the gesture a lie.
             */
            .setContentIntent(cancel)
            .setDeleteIntent(cancel)
            .setAutoCancel(true)
            .addAction(0, "Not now", cancel)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // The countdown updates every second and none of those updates is news, so only
            // the first of them is allowed to come forward.
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

        // The type constant arrived in Android 10 but is compiled in rather than looked up,
        // and ServiceCompat drops it on the versions that predate typed services — so this
        // is correct at every level this app runs at.
        @SuppressLint("InlinedApi")
        ServiceCompat.startForeground(
            this,
            AUTO_PLAY_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    /**
     * A channel of its own rather than the player's.
     *
     * These are the only notifications lugu posts that nobody asked for at the moment they
     * appear, so they are the only ones somebody might reasonably want to silence without
     * silencing the player. Separating them is what makes that possible.
     *
     * **It is a high-importance channel, and that is the point of it.** In Android's ordinary
     * notification layout the action buttons live in the *expanded* view: at low importance
     * the notification arrives collapsed and "Not now" is behind a two-finger pull, which is
     * longer than the thing it is offering to interrupt. High importance brings it forward
     * over whatever is on screen with the button already showing, which is the only way a
     * one-second offer can be taken.
     *
     * Coming forward is not the same as making a noise, and this makes none: no sound, no
     * vibration, and only the first post of a countdown is allowed to alert at all.
     *
     * The id is versioned because a channel's importance belongs to the listener once it
     * exists — `createNotificationChannel` cannot raise it afterwards, by design, since that
     * is how an app would undo someone turning it down. The old channel is deleted rather
     * than left behind, so settings does not list a channel that nothing posts to.
     */
    private fun ensureAutoPlayChannel() {
        val notifications = NotificationManagerCompat.from(this)
        notifications.deleteNotificationChannel(RETIRED_AUTO_PLAY_CHANNEL_ID)
        val channel = NotificationChannelCompat.Builder(
            AUTO_PLAY_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_HIGH,
        )
            .setName("Starting playback")
            .setDescription("Shown for a few seconds when a book is about to start by itself")
            .setSound(null, null)
            .setVibrationEnabled(false)
            .build()
        notifications.createNotificationChannel(channel)
    }

    /** Whether anything worth playing to is attached — headphones, a car, a cable. */
    private fun hasListenableOutput(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        val carMode = isCarMode()
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            AudioRoutes.classify(it.type, carMode) != AudioRouteClass.OTHER
        }
    }

    /**
     * Whether the audio belongs to something else.
     *
     * Nothing of ours is playing at this point — the wait has only just ended — so anything
     * `isMusicActive` reports is another app, and taking the audio from it would be exactly
     * the behaviour that makes people uninstall a media app.
     */
    private fun someoneElseHasTheAudio(): Boolean {
        if (player.isPlaying) return false
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audioManager.isMusicActive
    }

    private fun onACall(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audioManager.mode == AudioManager.MODE_IN_CALL ||
            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION ||
            audioManager.mode == AudioManager.MODE_RINGTONE
    }

    /** The player's state as [PersistencePolicy] understands it. */
    private fun phase(): PlaybackPhase = when {
        player.mediaItemCount == 0 -> PlaybackPhase.EMPTY
        player.playbackState == Player.STATE_IDLE -> PlaybackPhase.IDLE
        player.playbackState == Player.STATE_ENDED -> PlaybackPhase.ENDED
        else -> PlaybackPhase.LOADED
    }

    /**
     * Keeps the notification through a pause, by keeping the service in the foreground.
     *
     * This is Media3's own hook rather than a hand-rolled `stopForeground(STOP_FOREGROUND_DETACH)`
     * and re-post, and using it matters: the detach-and-re-post *is* what Media3 already does
     * on a pause, so doing it again here would be two owners fighting over one notification.
     * What Media3 will not do is stay in the foreground indefinitely — its own grace period is
     * capped at ten minutes — and past that the process is reclaimable, which is what takes
     * the notification with it. So the only thing overridden is the foreground decision, and
     * everything about building, posting, updating and removing the notification is left where
     * it was.
     *
     * The framework's answer is kept as a floor rather than replaced: while something is
     * playing, or inside the grace period, Media3 already wants the foreground and the reasons
     * it wants it are not ours to second-guess.
     *
     * A refused foreground start is not a crash here — Media3 catches it and reports it
     * through the service listener, which writes it to the diary.
     */
    override fun onUpdateNotificationAsync(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ): ListenableFuture<Void> {
        val held = PersistencePolicy.holdsForegroundWhilePaused(
            persistence = currentSettings.notification,
            phase = phase(),
            hasPlayed = hasPlayedSinceCreate,
            foregroundNotificationIsDismissible = FOREGROUND_NOTIFICATION_IS_DISMISSIBLE,
        )
        if (held != foregroundHeld) {
            foregroundHeld = held
            // "The notification vanished" is a complaint with nothing in it unless the record
            // says whether lugu was still holding on to it at the time.
            diary.record(
                NOTIFICATION_PERSISTENCE,
                playbackDetail(if (held) "held through the pause" else "let go"),
            )
        }
        // The auto-play wait is added here rather than folded into the policy above, because
        // it is not a persistence decision: nothing has played, nothing is paused, and the
        // hold lasts seconds rather than until the listener comes back.
        return super.onUpdateNotificationAsync(
            session,
            startInForegroundRequired || held || autoPlayWaiting,
        )
    }

    /**
     * Loads the last thing played, paused, at its stored position.
     *
     * The point is that a headset press or the notification's play button then starts it with
     * nothing to navigate first — the complaint being answered is having to find the book
     * again after the app has been closed for a while. It is emphatically not a resume:
     * nothing here calls `play`, and an arming that started playing would be the exact
     * behaviour — an app seizing the audio on being opened — that the setting next to this one
     * exists to refuse.
     *
     * Silent in every case where there is nothing to do. Resolution failure is one of those
     * cases rather than an error: with no connection and no download there is genuinely
     * nothing to arm, and saying so on the screen would be an error message for something the
     * listener never asked for. [MediaResolver] prefers a downloaded copy, so a downloaded
     * book arms with the network off.
     */
    private fun armLastPlayed() {
        val phase = phase()
        val wanted = PersistencePolicy.armsOnOpen(
            persistence = currentSettings.notification,
            phase = phase,
            wantsToPlay = player.playWhenReady,
            alreadyArming = armingJob?.isActive == true,
        )
        if (!wanted) return

        if (phase == PlaybackPhase.IDLE) {
            // The item is still in the player; it was only stopped, most likely by the
            // notification being swiped away. Preparing it again costs nothing and does not
            // start it, because `playWhenReady` is false and has just been checked.
            player.prepare()
            diary.record(ARMED, playbackDetail("prepared again"))
            return
        }

        armingJob = scope.launch {
            val resolved = withContext(Dispatchers.IO) {
                runCatching { resumptionResolver.resolveLastPlayed() }.getOrNull()
            }
            if (resolved == null) {
                diary.record(ARMED, "nothing to arm")
                return@launch
            }
            // Resolving takes a round trip, and a play request can arrive inside it. That
            // request wins: replacing a book somebody has just started with a different one
            // would be the worst thing this feature could do.
            if (player.mediaItemCount > 0 || player.playWhenReady) {
                diary.record(ARMED, "dropped, something else started first")
                return@launch
            }
            stateHolder.set(resolved.nowPlaying)
            player.setMediaItems(
                resolved.mediaItems,
                resolved.startTrackIndex,
                resolved.startPositionMs,
            )
            // Prepared, never played. Buffering the first bytes is what makes the press
            // that follows instant, and it is the whole difference between armed and loaded.
            player.prepare()
            // Written down because something appearing in the notification shade that the
            // listener did not ask for is exactly the kind of thing they will want to check.
            diary.record(ARMED, playbackDetail())
        }
    }

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
                applyCarSleepRule()
                evaluateSleepTimer()
                // Before the metadata refresh, so a skip and the chapter it lands in are
                // published on the same tick rather than a beat apart.
                enforceSkipRegions()
                refreshChapterMetadata()
            }
        }
    }

    private fun evaluateSleepTimer() {
        val mode = stateHolder.sleepTimer.value.mode ?: return
        if (sleepSuspendedForCar) return
        if (!player.isPlaying) return

        val context = stateHolder.nowPlaying.value ?: return
        val position = currentAbsoluteSec() ?: return

        val speed = player.playbackParameters.speed
        // [SleepCountdown] rather than the arithmetic directly: a chapter count has to be
        // resolved from where the timer was armed, and a chapter boundary has to be caught
        // by a loop that only sees the position every half second.
        val remaining = SleepCountdown.remainingSec(
            mode = mode,
            chapters = context.chapters,
            positionSec = position,
            armedAtPositionSec = stateHolder.sleepArmedAtPositionSec,
            speed = speed,
        )

        if (remaining == null) {
            // A chapter-based timer on something with no chapters can never come due. It is
            // left armed rather than cancelled — the next item may well have chapters — but
            // it is said once, because "the timer did not fire" is otherwise a bug report
            // with nothing in it.
            if (!unfireableTimerRecorded) {
                unfireableTimerRecorded = true
                diary.record(SLEEP_TIMER_WAITING, playbackDetail("no chapters to count"))
            }
            return
        }

        val volume = SleepFade.volumeFor(remaining, currentSettings.sleep.fadeSeconds)
        stateHolder.updateSleepTimer(remaining, isFading = volume < 1.0f)
        player.volume = volume

        if (SleepCountdown.isDue(remaining, SLEEP_TICK_MS, speed)) {
            // Declared before the pause, so the stop that follows is attributed to the
            // timer rather than recorded as one nobody asked for.
            stopAttributor.declare(StopAttributor.REASON_SLEEP_TIMER, System.currentTimeMillis())
            diary.record(PlaybackEvent.SLEEP_TIMER_FIRED, playbackDetail())
            // Cleared before the pause rather than after it: the pause callback can arrive
            // inside `pause()`, and it now decides whether a pause ends the timer. A timer
            // still armed at that moment would be reported as cancelled by the listener.
            stateHolder.clearSleepTimer()
            player.pause()
            // Restore the volume so the next play is not silent — the commonest way a
            // fade-out implementation leaves the app apparently broken.
            player.volume = 1.0f
            // Whatever played through the fade was not really heard, so the next play
            // starts before it rather than where the ear gave up.
            pendingSleepRewindSec = currentSettings.sleep.rewindOnWakeSec.toDouble().takeIf { it > 0 }
            persistPosition(reason = "sleep-timer")
            syncShakeListening()
        }
    }

    /**
     * The current episode's skip plan, or null.
     *
     * `flatMapLatest` because the trim is stored per show, so *which* preference flow to
     * watch is decided by what is playing and has to be swapped when that changes.
     * Combined with the settings so that turning announcing off is felt on the next skip
     * rather than only on the next episode.
     */
    @kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun skipPlans(): Flow<SkipPlan?> =
        stateHolder.nowPlaying.flatMapLatest { now ->
            val episodeId = now?.episodeId
            if (now == null || episodeId == null) {
                flowOf(null)
            } else {
                // Keyed on the library item, which for an episode is the show: the trim
                // belongs to the podcast, exactly as the speed does.
                combine(
                    playbackPrefs.observeTrimFor(now.libraryItemId),
                    playbackPrefs.settings,
                ) { trim, settings ->
                    SkipPlan.forEpisode(
                        libraryItemId = now.libraryItemId,
                        episodeId = episodeId,
                        durationSec = now.durationSec,
                        chapters = now.chapters,
                        trim = trim,
                        announces = settings.skip.announceSkips,
                    )
                }
            }
        }

    /**
     * Jumps a podcast episode over its intro, its outro and its marked adverts.
     *
     * The decision belongs to [SkipRegionEnforcer] and the seek belongs here, because the
     * seek has to go back through [AbsoluteTiming]: the enforcer works, like everything
     * that records, syncs or resumes a position, in whole-item seconds, and only this has
     * the track list to turn one of those into a player position.
     *
     * On the existing half-second loop rather than a ticker of its own — nothing wakes up
     * for this, and [skipPlan] is null for every book and every untrimmed show, so the
     * ordinary cost is one null check.
     */
    private fun enforceSkipRegions() {
        val plan = skipPlan ?: return
        val context = stateHolder.nowPlaying.value ?: return
        val position = currentAbsoluteSec() ?: return
        val decision = skipEnforcer.decide(plan, position, player.isPlaying) ?: return

        // Announced before the seek, and before the end, so the notice is about the
        // episode still in the player. A correction the listener cannot see is
        // indistinguishable from lost audio.
        decision.undo?.let(stateHolder::setUndoableJump)

        when (decision) {
            is SkipDecision.Seek -> {
                diary.record(
                    SKIPPED_REGION,
                    playbackDetail("skipped the ${decision.skipped.reason.label}"),
                )
                seekToAbsolute(context, decision.toPositionSec)
            }

            is SkipDecision.EndItem -> {
                diary.record(
                    SKIPPED_REGION,
                    playbackDetail("ended on the ${decision.skipped.reason.label}"),
                )
                // The end of the episode reached the way every episode reaches it. Seeking
                // to the tail of the last track puts Media3 in STATE_ENDED, and
                // [PersistenceListener] then persists it — finished, because the position
                // is inside FINISHED_TAIL_SEC of the duration — and calls [continueToNext].
                // Stopping here instead would leave the queue, the continuation and the
                // progress sync all unrun, and the episode would sit at its very end
                // marked unfinished forever.
                seekToAbsolute(context, context.durationSec)
            }
        }
    }

    /**
     * Whole-item seconds to a seek — the only place a position from the model is ever
     * turned into one.
     *
     * Seeks the raw [player] rather than the session's wrapper, following
     * [applyRewindOnResume]: this is an internal correction, and [ChapterAwarePlayer]
     * remaps the transport commands for a listener pressing buttons.
     */
    private fun seekToAbsolute(context: NowPlaying, absoluteSec: Double) {
        val safe = absoluteSec.coerceAtLeast(0.0)
        // An item that arrived without a track list would otherwise map every position to
        // track 0 offset 0, which is a seek to the beginning wearing a skip's clothes.
        if (context.tracks.isEmpty()) {
            player.seekTo((safe * 1000).toLong())
            return
        }
        val target = AbsoluteTiming.toTrack(context.tracks, safe)
        player.seekTo(target.trackIndex, target.positionMs)
    }

    /**
     * Holds the sleep timer while a car is connected, and gives it back afterwards.
     *
     * The rule and its reasoning are in [CarSleepRule]; this is the part that has a player
     * to act on. Evaluated on the tick as well as on connect and disconnect, because a car
     * can also be entered through the system's own car UI mode, which sends nobody a
     * callback — and the timer state is read first so a phone with no timer set never asks
     * the system anything.
     */
    private fun applyCarSleepRule() {
        val armed = stateHolder.sleepTimer.value.isArmed
        if (!armed && !sleepSuspendedForCar) return

        when (CarSleepRule.actionFor(isCarMode(), armed = armed, suspended = sleepSuspendedForCar)) {
            SleepTimerCarAction.NOTHING -> Unit

            SleepTimerCarAction.SUSPEND -> {
                sleepSuspendedForCar = true
                // A fade already under way would otherwise leave the book quiet for the
                // rest of the journey with nothing to explain it.
                player.volume = 1.0f
                diary.record(SLEEP_TIMER_HELD, playbackDetail("a car is connected"))
                syncShakeListening()
            }

            SleepTimerCarAction.RELEASE -> {
                sleepSuspendedForCar = false
                // Re-armed from here rather than resumed: a timer that spent the journey
                // suspended is long overdue, and resuming it would stop the audio the
                // moment the car was left.
                currentAbsoluteSec()?.let { position ->
                    stateHolder.armSleepTimer(stateHolder.sleepTimer.value.mode, position)
                }
                diary.record(SLEEP_TIMER_HELD, playbackDetail("released, the car has gone"))
                syncShakeListening()
            }
        }
    }

    /**
     * A pause either leaves the timer armed or ends it, as the setting says.
     *
     * Surviving is the default and needs nothing done to it: remaining time is measured in
     * playback seconds, so a pause simply stops spending it. What had to be written is the
     * other half — that somebody who wants a pause to cancel the timer gets it cancelled —
     * and, either way, a line saying which happened. The complaint upstream has open as
     * app#1317 is not that the wrong thing happens but that nothing says what did.
     */
    private fun applySleepTimerPauseRule() {
        if (!stateHolder.sleepTimer.value.isArmed) return
        if (currentSettings.sleep.survivesPause) {
            diary.record(SLEEP_TIMER_HELD, "still armed through the pause")
            return
        }
        stateHolder.clearSleepTimer()
        sleepSuspendedForCar = false
        // The fade may have started before the pause, and a timer that is gone must not
        // leave the next play quiet.
        player.volume = 1.0f
        diary.record(SLEEP_TIMER_CANCELLED, "the pause ended it, as configured")
    }

    /**
     * Keeps the chapter title in the session's metadata current.
     *
     * Written as both the subtitle and the display description because hosts differ in
     * which of the two they draw, and each maps to its own key on the platform session, so
     * saying it twice costs a string and reaches both. The reasoning, and why the *position*
     * cannot be told the same story, is in [NowPlayingMetadata] and
     * [LuguNotificationProvider].
     *
     * The item is replaced in place rather than the playlist rebuilt: with the URI and the
     * cache key unchanged Media3 updates the metadata without touching the media source, so
     * nothing rebuffers, no position moves and no track transition is reported. Only a real
     * change is written, because every write reaches every connected controller.
     */
    private fun refreshChapterMetadata() {
        val context = stateHolder.nowPlaying.value ?: return
        val current = player.currentMediaItem ?: return
        val trackIndex = player.currentMediaItemIndex
        val position = currentAbsoluteSec() ?: return

        val itemKey = "${context.ledgerId}#$trackIndex"
        val chapterIndex = NowPlayingMetadata.chapterIndexAt(context.chapters, position)
        if (itemKey == metadataItemKey && chapterIndex == metadataChapterIndex) return
        metadataItemKey = itemKey
        metadataChapterIndex = chapterIndex

        val subtitle = NowPlayingMetadata.subtitleFor(context.chapters, chapterIndex, context.author)
        player.replaceMediaItem(
            trackIndex,
            current.buildUpon()
                .setMediaMetadata(
                    current.mediaMetadata.buildUpon()
                        .setSubtitle(subtitle)
                        .setDescription(subtitle)
                        .build(),
                )
                .build(),
        )
    }

    /**
     * Registers or drops the accelerometer to match the timer.
     *
     * Listening is worth its battery only while the timer is armed, the setting is on,
     * and something is actually playing — outside that the answer to a shake would be to
     * do nothing, and a sensor registered to do nothing is a bug the user cannot see. A
     * timer held for a car is the same case: there is nothing to extend.
     */
    private fun syncShakeListening() {
        val sleep = currentSettings.sleep
        val wanted = sleep.shakeSensitivity.takeIf {
            sleep.shakeToExtend &&
                stateHolder.sleepTimer.value.isArmed &&
                !sleepSuspendedForCar &&
                player.isPlaying
        }
        if (wanted == shakeListeningAt) return
        shakeListeningAt = wanted
        if (wanted != null) shakeDetector?.start(wanted) else shakeDetector?.stop()
    }

    /**
     * Buys more time without finding the screen in the dark.
     *
     * Re-arming from the current position is what makes this work mid-fade: the timer has
     * already run down, so extending the old one would expire again immediately.
     */
    private fun extendSleepTimerOnShake() {
        val timer = stateHolder.sleepTimer.value
        if (!timer.isArmed) return
        val position = currentAbsoluteSec() ?: return
        val minutes = currentSettings.sleep.extendMinutes
        stateHolder.armSleepTimer(SleepTimer.extend(timer.mode, minutes), position)
        // A shake usually arrives during the fade, and an extension nobody can hear is
        // indistinguishable from one that did not work.
        player.volume = 1.0f
        diary.record(PlaybackEvent.SLEEP_TIMER_EXTENDED, "by $minutes min, after a shake")
    }

    /**
     * An output went away.
     *
     * The pause itself is Media3's, through `setHandleAudioBecomingNoisy`. All that
     * happens here is remembering that a disconnect is what stopped playback, so a later
     * reconnection may undo it — and declaring the stop, so it is not recorded as one
     * nobody asked for.
     */
    private fun onAudioRouteLost(routeClass: AudioRouteClass) {
        val wasPlaying = player.isPlaying || player.playWhenReady
        diary.record(
            PlaybackEvent.AUDIO_ROUTE_LOST,
            playbackDetail("${routeClass.name.lowercase()}, was playing: $wasPlaying"),
        )
        if (!wasPlaying || !currentSettings.route.pauseOnDisconnect) return

        stopAttributor.declare(StopAttributor.REASON_ROUTE_LOST, System.currentTimeMillis())
        pausedByRoute = routeClass
        pausedByRouteAtMs = System.currentTimeMillis()
    }

    /**
     * An output came back.
     *
     * The decision belongs to the device that has just arrived rather than to the one
     * that left: a car disconnecting also ends car mode, so the class recorded at
     * disconnect time is the less reliable of the two. What is required from the earlier
     * disconnect is only that there *was* one — a book the listener paused deliberately
     * is never restarted by plugging something in.
     *
     * The window exists because "the headphones came back" stops meaning "carry on" after
     * long enough. Finding a book playing the next morning is not a feature.
     */
    private fun onAudioRouteGained(routeClass: AudioRouteClass) {
        diary.record(PlaybackEvent.AUDIO_ROUTE_GAINED, routeClass.name.lowercase())

        if (pausedByRoute == null) return
        val wanted = when (routeClass) {
            AudioRouteClass.CAR -> currentSettings.route.resumeInCar
            AudioRouteClass.HEADPHONES -> currentSettings.route.resumeOnHeadphones
            AudioRouteClass.OTHER -> false
        }
        if (!wanted) return
        if (System.currentTimeMillis() - pausedByRouteAtMs > ROUTE_RESUME_WINDOW_MS) return
        if (player.mediaItemCount == 0) return

        pausedByRoute = null
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    /**
     * Enough about the current playback to tell two stops apart when the diary is read
     * back: what was playing, where it had reached, and whether the bytes were arriving
     * over the network or coming off the disk.
     */
    private fun playbackDetail(extra: String? = null): String {
        val title = stateHolder.nowPlaying.value?.title ?: "nothing loaded"
        val position = currentAbsoluteSec()?.let { "at ${it.roundToInt()}s" } ?: "no position"
        val source = if (isStreaming()) "streaming" else "downloaded"
        return listOfNotNull(extra, title, position, source).joinToString(", ")
    }

    /** A downloaded track plays from a local path; anything over http is coming down a wire. */
    private fun isStreaming(): Boolean {
        val scheme = player.currentMediaItem?.localConfiguration?.uri?.scheme ?: return false
        return scheme.startsWith("http")
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
     * Applies the rewind, once, at the moment playback resumes.
     *
     * Doing it here rather than at pause time is the whole point: rewinding on pause
     * makes the stored position drift backwards on every pause, which is how a book
     * ends up minutes out of place (app #1147, #622). Computed from the real pause
     * duration, so a headset stutter moves nothing.
     *
     * Two corrections can be owed at once — the smart rewind for the length of the pause,
     * and the sleep timer's own rewind for the part that was slept through. They answer
     * the same question, so the larger wins rather than the two being added: someone who
     * fell asleep did not miss fifty seconds because two features both think they missed
     * some.
     */
    private fun applyRewindOnResume() {
        val sleepRewindSec = pendingSleepRewindSec
        pendingSleepRewindSec = null
        val pausedAt = pausedAtWallClockMs
        pausedAtWallClockMs = null

        val pausedForMs = pausedAt?.let { System.currentTimeMillis() - it } ?: 0L
        val smartRewindSec = SmartRewind.rewindSeconds(pausedForMs)
        val rewindSec = maxOf(smartRewindSec, sleepRewindSec ?: 0.0)
        if (rewindSec <= 0.0) return

        val context = stateHolder.nowPlaying.value ?: return
        val absolute = currentAbsoluteSec() ?: return
        val target = (absolute - rewindSec).coerceAtLeast(0.0)

        val position = AbsoluteTiming.toTrack(context.tracks, target)
        player.seekTo(position.trackIndex, position.positionMs)
        // Announce it: an automatic correction the listener cannot see is a bug to them.
        val notice = if (sleepRewindSec != null && sleepRewindSec >= smartRewindSec) {
            "Rewound ${rewindSec.roundToInt()}s from where you fell asleep"
        } else {
            SmartRewind.describe(pausedForMs)
        }
        stateHolder.setRewindNotice(notice)
    }

    private inner class PersistenceListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val now = System.currentTimeMillis()
            buttonClassifier.onPlaybackStateChanged(isPlaying, now)
            if (!isPlaying) {
                lastTickWallClockMs = 0L
                pausedAtWallClockMs = now
                persistPosition(reason = "pause")
                applySleepTimerPauseRule()
                SyncScheduler.flushNow(this@LuguPlaybackService)
            } else {
                hasPlayedSinceCreate = true
                applyRewindOnResume()
                lastTickWallClockMs = System.currentTimeMillis()
            }
            syncShakeListening()
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

                // Synchronously, before the coroutine below: the enforcer has to know
                // whether this seek was its own or the listener's before the next tick.
                // A deliberate seek into a region is the listener overriding the skip, and
                // it is also what makes the Undo on a skip notice work rather than be
                // undone again half a second later.
                skipEnforcer.onSeek(skipPlan, to)

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
     * The record of why playback stopped, written as it happens.
     *
     * Separate from [PersistenceListener] because it does something different: that one
     * keeps the listener's position safe, this one keeps the *reason* recoverable
     * afterwards. Everything here is a write to the diary and, for a transient network
     * failure, one bounded attempt to carry on.
     */
    private inner class DiagnosticsListener : Player.Listener {

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            lastStopReason = if (playWhenReady) StopAttributor.REASON_UNREPORTED else reason
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                // A stretch of successful playback is what makes the next failure a new
                // one rather than a continuation of the last.
                retryAttempts = 0
                pausedByRoute = null
                networkStall = null
                diary.record(PlaybackEvent.PLAYING, playbackDetail())
                return
            }

            val verdict = stopAttributor.classify(
                StopSignals(
                    playbackState = player.playbackState,
                    // A player that still wants to play was not stopped by a
                    // `playWhenReady` change, so no reason from one applies.
                    playWhenReadyChangeReason = if (player.playWhenReady) {
                        StopAttributor.REASON_UNREPORTED
                    } else {
                        lastStopReason
                    },
                    suppressionReason = player.playbackSuppressionReason,
                    hasError = player.playerError != null,
                ),
                System.currentTimeMillis(),
            )
            recordStop(verdict)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                // Idle after a failure is the state a stalled network leaves behind, and
                // nothing restarts a player that is sitting in it.
                Player.STATE_IDLE -> diary.record(PlaybackEvent.IDLE, playbackDetail())
                Player.STATE_ENDED -> diary.record(PlaybackEvent.ENDED, playbackDetail())
                Player.STATE_BUFFERING -> recordBufferingSparingly()
                else -> Unit
            }
        }

        /**
         * The callback that tells "something else took the audio" apart from every other
         * kind of stop. A phone call, a navigation prompt, a car head unit taking the
         * output — all of them arrive here and nowhere else.
         *
         * Except a duck. When the book is lowered rather than stopped there is no callback
         * at all: Media3 applies a volume multiplier inside the renderers and reports
         * nothing, and the player goes on saying it is playing, which it is. So a quiet
         * moment with no line here is the record of a duck, and the line that says which of
         * the two was going to happen is written by [applyDucking].
         */
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                diary.record(PlaybackEvent.UNSUPPRESSED, playbackDetail())
            } else {
                diary.record(
                    PlaybackEvent.SUPPRESSED,
                    playbackDetail(suppressionReasonName(playbackSuppressionReason)),
                )
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            diary.record(
                PlaybackEvent.ERROR,
                playbackDetail("${error.errorCodeName}: ${error.message.orEmpty()}"),
            )
            // Recorded here, at the failure, because by the time the network returns the
            // player has been idle for minutes and no longer holds either fact.
            // `playWhenReady` survives an error — the player still wants to play, it simply
            // cannot — so it is the honest answer to "was anybody listening".
            networkStall = NetworkStall(
                atMs = System.currentTimeMillis(),
                networkFault = retryPolicy.isTransient(error.errorCode),
                wantedToPlay = player.playWhenReady,
            )
            scheduleRetry(error, resumeAfterwards = player.playWhenReady)
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            this@LuguPlaybackService.audioSessionId = audioSessionId
            // The loudness effect is bound to the session, not to the player, so a new
            // session means the old effect is attached to nothing.
            loudnessBoost?.apply(audioSessionId, currentSettings.audio.volumeBoostDb)
        }
    }

    /**
     * Buffering happens on every seek and every track change, and a diary full of it
     * would evict the lines worth reading. One record per interval is enough to show the
     * pattern that matters: repeated stalls before a stop means the network, not the app.
     */
    private fun recordBufferingSparingly() {
        val now = System.currentTimeMillis()
        if (now - lastBufferingRecordMs < BUFFERING_RECORD_INTERVAL_MS) return
        lastBufferingRecordMs = now
        diary.record(PlaybackEvent.BUFFERING, playbackDetail())
    }

    /** Writes one stop to the diary under the name that says what kind it was. */
    private fun recordStop(verdict: StopVerdict) {
        val detail = playbackDetail(verdict.detail)
        when (verdict.cause) {
            // The end of an item is already recorded by the state change; recording it
            // twice would only make the diary harder to read.
            StopCause.ENDED -> Unit
            StopCause.FAILED -> diary.record(PlaybackEvent.IDLE, detail)
            StopCause.FOCUS_LOST -> diary.record(PlaybackEvent.SUPPRESSED, detail)
            StopCause.ROUTE_LOST -> diary.record(PlaybackEvent.AUDIO_ROUTE_LOST, detail)
            StopCause.INTERNAL, StopCause.REQUESTED -> diary.record(PlaybackEvent.PAUSED, detail)
            StopCause.UNEXPECTED -> diary.record(PlaybackEvent.UNEXPECTED_STOP, detail)
        }
    }

    /**
     * Tries once more after a network failure, up to the policy's limit.
     *
     * This is the one likely cause of "it stopped on its own" that can be fixed rather
     * than only recorded: ExoPlayer's response to a failed read is to go idle and wait
     * for somebody to prepare it again, and until now nobody did. Each attempt is written
     * to the diary, so a retry that did not help is as visible as one that did.
     */
    private fun scheduleRetry(error: PlaybackException, resumeAfterwards: Boolean) {
        val delayMs = retryPolicy.retryDelayMs(error.errorCode, retryAttempts) ?: return
        retryAttempts += 1
        val attempt = retryAttempts

        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            diary.record(
                PlaybackEvent.ERROR,
                "retrying after ${error.errorCodeName}, attempt $attempt of ${PlaybackRetryPolicy.MAX_ATTEMPTS}",
            )
            player.prepare()
            if (resumeAfterwards) player.play()
        }
    }

    /**
     * The end of a book, and the moment a queue is for.
     *
     * Done here rather than in the UI because this is exactly when the UI is least
     * likely to exist: a book finishing in a car, or with the screen off. The resolver
     * decides *what* comes next and whether it may start itself; this only loads it.
     *
     * @param startedByListener true when a headset button asked for this rather than a book
     *   ending. The "ask before a suggestion" setting exists so nothing new begins without
     *   being asked, and a press is being asked — so an explicit press plays, and the
     *   notice says it is playing rather than waiting.
     */
    private fun continueToNext(startedByListener: Boolean = false) {
        val finished = stateHolder.nowPlaying.value ?: return
        scope.launch {
            val continuation = withContext(Dispatchers.IO) {
                continuationResolver.resolveNext(finished.libraryItemId, finished.episodeId)
            } ?: return@launch

            diary.record(
                PlaybackEvent.CONTINUATION,
                "${continuation.resumption.nowPlaying.title}, started automatically: ${continuation.autoStart}",
            )

            if (startedByListener) stateHolder.setContinuationNotice(continuation.reason, cued = false)

            stateHolder.set(continuation.resumption.nowPlaying)
            player.setMediaItems(
                continuation.resumption.mediaItems,
                continuation.resumption.startTrackIndex,
                continuation.resumption.startPositionMs,
            )
            player.prepare()
            // Cueing without playing is the whole of the "ask first" setting: the next
            // book is loaded and one press away, and nothing started on its own.
            if (continuation.autoStart || startedByListener) player.play()
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
            .setSessionCommand(
                SessionCommand(NotificationLayout.COMMAND_CHAPTER_PREVIOUS, android.os.Bundle.EMPTY),
            )
            .setDisplayName("Previous chapter")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_NEXT)
            .setSessionCommand(
                SessionCommand(NotificationLayout.COMMAND_CHAPTER_NEXT, android.os.Bundle.EMPTY),
            )
            .setDisplayName("Next chapter")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_PLAYBACK_SPEED)
            .setSessionCommand(SessionCommand(COMMAND_SPEED_CYCLE, android.os.Bundle.EMPTY))
            .setDisplayName("Speed")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    /**
     * The buttons offered to the notification, the lock screen and the platform session.
     *
     * The listener's own choices come first, in their order, because the first two take the
     * places either side of play-pause. The car's chapter and speed buttons are appended,
     * but only while a car is actually connected: they reach a car through this list — a
     * projection host reads the platform session's custom actions, which Media3 builds from
     * exactly these preferences — and if they were always present, "no buttons" would still
     * leave three of them in the expanded notification on a phone.
     */
    private fun notificationLayout(): List<CommandButton> {
        val chosen = NotificationLayout.buttonsFor(currentSettings)
        return if (carControllers.isEmpty()) chosen else chosen + carCommands
    }

    /**
     * Hands the current layout to everything already connected.
     *
     * Media3 broadcasts this to every controller including the one that owns the
     * notification, and rebuilds the notification when the list has really changed. Nothing
     * about the *commands* moves — those are granted in full at connection time — so this
     * can be called at any moment without a controller having to re-negotiate anything.
     */
    private fun pushNotificationLayout(target: MediaSession? = session) {
        target?.setMediaButtonPreferences(notificationLayout())
    }

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // A car projection connecting is the most reliable "this is a car" signal
            // there is, and it needs no permission — see [AudioRoutes].
            if (CAR_CONTROLLER_PACKAGES.any { controller.packageName.startsWith(it) }) {
                carControllers += controller.packageName
            }

            // The base set depends on whether the user trusts this controller, which is
            // Media3's own default behaviour since 1.11 and what the single-argument
            // `AcceptedResultBuilder(session)` — now deprecated — could not express. An
            // untrusted controller is any app that is not lugu, not the system, and has
            // neither media-control permission nor an enabled notification listener; the
            // notification controller, the app's own controller and a car projection host are
            // all trusted. Giving the untrusted case the full set would hand any installed
            // app the whole browse tree, which is exactly what these constants exist to stop.
            val commands = if (controller.isTrusted) {
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            } else {
                MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_SESSION_AND_LIBRARY_COMMANDS
            }.buildUpon()

            carCommands.forEach { button -> button.sessionCommand?.let { commands.add(it) } }
            NotificationLayout.allCommands().forEach { commands.add(it) }
            // Not a button anywhere. It is how the app tells the service it has come to the
            // foreground, which is the one thing the service cannot see for itself.
            commands.add(
                SessionCommand(PersistencePolicy.COMMAND_ARM_LAST_PLAYED, android.os.Bundle.EMPTY),
            )

            val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands.build())

            // The controller behind the notification is given the layout and nothing else.
            // A custom layout would win over it when the listener has chosen no buttons at
            // all — Media3 falls back to deriving preferences from the layout when the
            // preferences are empty — and the promise on the settings screen is that no
            // buttons means play-pause alone.
            if (session.isMediaNotificationController(controller)) {
                result.setMediaButtonPreferences(notificationLayout())
            } else {
                result.setCustomLayout(carCommands)
            }
            return result.build()
        }

        /**
         * A car that has just arrived changes what the layout should contain, and its own
         * connection result was built before it was known to be a car. Done here rather
         * than in [onConnect] because a layout pushed to a controller that has not finished
         * connecting is discarded.
         */
        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            pushNotificationLayout(session)
            // A car arriving is also the moment an armed sleep timer has to be held, and
            // waiting for the next tick to notice would leave half a second in which it
            // could still fire.
            applyCarSleepRule()
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            carControllers -= controller.packageName
            pushNotificationLayout(session)
            applyCarSleepRule()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                NotificationLayout.COMMAND_SKIP_BACK ->
                    seekRelative(-currentSettings.skipBackSec.toDouble())

                NotificationLayout.COMMAND_SKIP_FORWARD ->
                    seekRelative(currentSettings.skipForwardSec.toDouble())

                NotificationLayout.COMMAND_CHAPTER_PREVIOUS -> seekChapter(forward = false)
                NotificationLayout.COMMAND_CHAPTER_NEXT -> seekChapter(forward = true)
                COMMAND_SPEED_CYCLE -> cycleSpeed()
                PersistencePolicy.COMMAND_ARM_LAST_PLAYED -> armLastPlayed()
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /**
         * The headset's own side buttons, which are not the notification's.
         *
         * Intercepted here because Media3's default is to call `seekToNext()` and
         * `seekToPrevious()` on the player, and the listener may have asked for a chapter,
         * the next item, or nothing at all. Returning true claims the press so the default
         * never runs.
         */
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            if (intent.action != Intent.ACTION_MEDIA_BUTTON) return false
            val keyEvent = IntentCompat.getParcelableExtra(
                intent,
                Intent.EXTRA_KEY_EVENT,
                KeyEvent::class.java,
            ) ?: return false

            val button = when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
                -> MediaButton.NEXT

                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                -> MediaButton.PREVIOUS

                else -> return false
            }

            // Both halves of the press are claimed, and a held button repeats rather than
            // acting again: a headset that reports the release as well would otherwise skip
            // twice for one press.
            if (keyEvent.action != KeyEvent.ACTION_DOWN || keyEvent.repeatCount != 0) return true

            applyHeadsetPress(button)
            return true
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
            applyRememberedSpeed(resolved.nowPlaying)
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
            applyRememberedSpeed(resolved.nowPlaying)
            MediaSession.MediaItemsWithStartPosition(
                resolved.mediaItems,
                resolved.startTrackIndex,
                resolved.startPositionMs,
            )
        }
    }

    /**
     * The app has been swiped out of Recents.
     *
     * Whether that ends the session is the persistence setting's business — see
     * [PersistencePolicy.stopsWhenTaskRemoved]. The position is written down first either
     * way: swiping the app away must never be the thing that loses somebody's place.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val stops = PersistencePolicy.stopsWhenTaskRemoved(
            persistence = currentSettings.notification,
            phase = phase(),
            wantsToPlay = player.playWhenReady,
        )
        diary.record(
            PlaybackEvent.TASK_REMOVED,
            playbackDetail(
                "wanted to play: ${player.playWhenReady}, ${if (stops) "stopping" else "staying"}",
            ),
        )
        persistPosition(reason = "task-removed")
        if (stops) stopSelf()
    }

    override fun onDestroy() {
        // Recorded before anything is torn down, so the line exists even if the release
        // itself is what goes wrong. A destroy with no preceding pause, followed by
        // `process started`, is the system having reclaimed the process.
        diary.record(PlaybackEvent.SERVICE_DESTROYED, playbackDetail("was playing: ${player.isPlaying}"))

        persistPosition(reason = "destroy")
        autoPlayJob?.cancel()
        retryJob?.cancel()
        shakeDetector?.stop()
        shakeListeningAt = null
        routeWatcher?.stop()
        networkWatcher?.stop()
        loudnessBoost?.release()
        clearListener()
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

        /**
         * The waiting notification's own id and channel.
         *
         * Deliberately not Media3's. Sharing them would mean one notification that morphs
         * from the countdown into the player, which sounds tidier until Media3 posts an
         * update of its own mid-wait and silently takes the cancel button away with it.
         */
        const val AUTO_PLAY_NOTIFICATION_ID = 1002

        /**
         * Media3's own notification id, which this module does not choose and must not guess
         * at: [LuguNotificationProvider] extends the default provider without changing it, so
         * the constant is read from the class that owns it. It is what the waiting
         * notification waits to see before taking itself down.
         */
        const val MEDIA_NOTIFICATION_ID = DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID

        /** Suffixed, because the importance of a channel cannot be raised once it exists. */
        const val AUTO_PLAY_CHANNEL_ID = "lugu_auto_play_prompt"
        const val RETIRED_AUTO_PLAY_CHANNEL_ID = "lugu_auto_play"

        /** How long to wait for Media3's notification before removing this one regardless. */
        private const val HANDOVER_TIMEOUT_MS = 5_000L
        private const val HANDOVER_POLL_MS = 100L

        /**
         * How long to wait for the audio to move to the device that connected.
         *
         * Generous, because the thing being waited for genuinely varies: a headset that
         * announces itself and then plays its own connection chime can take several seconds
         * to finish handing over. Bounded because plenty of Bluetooth devices are not audio
         * devices at all, and a watch connecting must not leave a notification up for ever.
         */
        const val ROUTE_WAIT_TIMEOUT_MS = 20_000L

        /** How long after a disconnect a reconnection still means "carry on". */
        const val ROUTE_RESUME_WINDOW_MS = 30 * 60 * 1_000L

        /** One buffering record per interval, so stalls show as a pattern without flooding. */
        const val BUFFERING_RECORD_INTERVAL_MS = 30_000L

        /**
         * How long `onCreate` will wait for the settings it cannot apply later.
         *
         * Short enough that a slow disk delays the service rather than being reported as one
         * that never started, long enough that an ordinary cold-start read comfortably
         * finishes inside it. See [settingsAtStartup].
         */
        const val SETTINGS_READ_TIMEOUT_MS = 500L

        /**
         * Package prefixes that mean a car is driving the session. Prefixes rather than
         * exact names because the projection host has shipped under several ids.
         */
        val CAR_CONTROLLER_PACKAGES = listOf(
            "com.google.android.projection",
            "com.google.android.embedded.projection",
            "com.google.android.autoembedded",
            "com.google.android.gearhead",
            "com.android.car",
        )

        /** Within this much of the end counts as finished. */
        const val FINISHED_TAIL_SEC = 20.0

        /** A jump at least this large offers an undo, because it may not have been meant. */
        const val UNDO_PROMPT_SEC = 120.0

        /** Float comparison slack, so cycling never sticks on the preset it is already at. */
        const val SPEED_EPSILON = 0.001f

        /**
         * The chapter commands live in [NotificationLayout], which the notification and
         * the car both draw their buttons from. Speed is the car's alone.
         */
        const val COMMAND_SPEED_CYCLE = "io.github.lightheaded.lugu.SPEED_CYCLE"

        /**
         * A press of a headset's side button that did something out of the ordinary.
         *
         * Named here rather than in `PlaybackEvent` because that lives in another module;
         * it belongs there once this has proved worth keeping.
         */
        const val HEADSET_BUTTON = "headset button"

        /**
         * A network arrived, and what lugu decided to do about it.
         *
         * Written on every arrival, including the ones that change nothing, because the two
         * complaints this feature sits between — "it never came back" and "it came back on
         * its own in my pocket" — are told apart by exactly this line and by nothing else.
         * The verdict's own words say which condition refused it.
         */
        const val NETWORK_RETURNED = "network came back"

        /**
         * The sleep timer is armed and something has happened to it short of firing: it was
         * held for a car, given back, carried through a pause, or found nothing to count.
         *
         * All of them answer the same question — *why did the timer not fire* — which is
         * asked as often as the other one, and which nothing else in the record answers.
         */
        const val SLEEP_TIMER_HELD = "sleep timer held"
        const val SLEEP_TIMER_WAITING = "sleep timer cannot come due"
        const val SLEEP_TIMER_CANCELLED = "sleep timer cancelled"

        /**
         * What a short interruption will do to the book. Written when the policy changes,
         * because a duck itself is silent in every record the platform keeps.
         */
        const val AUDIO_FOCUS = "audio focus policy"

        /**
         * The moment lugu started holding the notification past a pause, and the moment it
         * let go again. Without them "the notification vanished" cannot be told from "lugu
         * never tried to keep it".
         */
        const val NOTIFICATION_PERSISTENCE = "notification persistence"

        /**
         * Something was loaded into the player without anybody pressing play. The other half
         * of the same complaint — "it armed something I did not ask for" — and the only
         * record that a book in the shade was put there by the app rather than by a press.
         */
        const val ARMED = "armed the last thing played"

        /**
         * A stretch the listener's trim settings asked to be jumped over.
         *
         * Recorded because a skip and a stall are the same event from the outside — the
         * audio moved somewhere the listener did not put it — and an over-eager trim
         * setting is a plausible answer to "it jumped and I do not know why".
         */
        const val SKIPPED_REGION = "skipped a region"

        /**
         * Whether a foreground service's notification can be swiped away.
         *
         * Android 14 made them dismissible; before that they cannot be, which is why Media3
         * leaves the foreground on a pause at all. See
         * [PersistencePolicy.holdsForegroundWhilePaused] for what follows from it.
         */
        val FOREGROUND_NOTIFICATION_IS_DISMISSIBLE =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
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
