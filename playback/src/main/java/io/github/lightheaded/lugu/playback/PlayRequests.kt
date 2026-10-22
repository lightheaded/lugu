package io.github.lightheaded.lugu.playback

import androidx.media3.common.MediaItem
import io.github.lightheaded.lugu.core.sync.ProgressJump
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The thing a play request asked for, reported while it is still being resolved. */
data class StartingItem(
    val libraryItemId: String,
    val episodeId: String?,
)

/**
 * Which play request is the one the listener made last.
 *
 * Starting an item that is not downloaded takes several round trips — the progress pull,
 * the play session, the item detail — and on a bad network each of them can take a read
 * timeout. Two requests can therefore be in flight at once, and they do not finish in the
 * order they were made. Without a rule, whichever answer arrived last was loaded, so an
 * old request that was slow could replace the new one the listener had just asked for.
 *
 * The rule is: **the newest request wins, and only the newest.** Each request takes a
 * ticket. An answer for any other ticket is dropped, and so is its failure, because an
 * error for something the listener has already moved away from is noise on the screen.
 *
 * [starting] is what the UI shows while the answer is on its way. The loaded item stays
 * in [PlaybackStateHolder] untouched, because the service persists the position of the
 * loaded item from there, and writing a requested item into it early would record the old
 * item's position against the new one.
 */
internal class PlayRequests {
    private val latest = AtomicLong(0)

    private val _starting = MutableStateFlow<StartingItem?>(null)
    val starting: StateFlow<StartingItem?> = _starting.asStateFlow()

    /** Records a new request and makes every earlier one stale. Returns its ticket. */
    fun begin(libraryItemId: String, episodeId: String?): Long {
        val ticket = latest.incrementAndGet()
        _starting.value = StartingItem(libraryItemId, episodeId)
        return ticket
    }

    /** True while no newer request, and no [abandon], has come after this ticket. */
    fun isLatest(ticket: Long): Boolean = latest.get() == ticket

    /** The request has been loaded or has failed. A stale ticket changes nothing. */
    fun end(ticket: Long) {
        if (isLatest(ticket)) _starting.value = null
    }

    /**
     * Drops whatever is in flight, with nothing to replace it.
     *
     * A pause pressed while an item is starting means "stop", and it must also stop the
     * start: an answer that lands a second later and starts the audio anyway would make the
     * pause button lie.
     */
    fun abandon() {
        latest.incrementAndGet()
        _starting.value = null
    }
}

/** Everything a resolved request hands to the player, worked out before anything is loaded. */
internal data class PreparedStart(
    val nowPlaying: NowPlaying,
    val jump: ProgressJump?,
    val mediaItems: List<MediaItem>,
    val startTrackIndex: Int,
    val startPositionMs: Long,
    val speed: Float,
)

/** What became of one play request. */
internal sealed interface StartOutcome {
    data object Loaded : StartOutcome

    /** A newer request, or a pause, came first. Nothing was loaded and nothing is reported. */
    data object Superseded : StartOutcome

    data class Failed(val cause: Throwable) : StartOutcome
}

/**
 * Runs one play request: resolve it, then load it only if it is still the newest.
 *
 * Split from [PlaybackConnection] so that the ordering can be tested without a media
 * controller. [prepare] is the slow half: it may use the network, and it also connects the
 * controller. [load] is the fast half and touches the player. It is deliberately not a
 * suspending function. The check sits between the two, after the last suspension, so no
 * stale answer can reach the player, and nothing can come between the check and the load.
 */
internal class PlayStarter(
    private val requests: PlayRequests,
    private val prepare: suspend (libraryItemId: String, episodeId: String?) -> Result<PreparedStart>,
    private val load: (PreparedStart) -> Unit,
) {
    suspend fun start(libraryItemId: String, episodeId: String?): StartOutcome {
        val ticket = requests.begin(libraryItemId, episodeId)
        val prepared = prepare(libraryItemId, episodeId)
        if (!requests.isLatest(ticket)) return StartOutcome.Superseded
        val outcome = prepared.fold(
            onSuccess = {
                load(it)
                StartOutcome.Loaded
            },
            onFailure = { StartOutcome.Failed(it) },
        )
        requests.end(ticket)
        return outcome
    }
}
