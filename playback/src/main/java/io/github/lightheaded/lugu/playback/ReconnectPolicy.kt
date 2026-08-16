package io.github.lightheaded.lugu.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper

/**
 * The stop a returning network is allowed to undo.
 *
 * Recorded at the moment playback failed rather than reconstructed afterwards, for the same
 * reason [StopAttributor] takes a declaration: by the time the connection comes back, the
 * player has been sitting idle for minutes and no longer holds the reason it stopped.
 */
data class NetworkStall(
    val atMs: Long,
    /**
     * Whether the failure was one [PlaybackRetryPolicy] recognises as being about the
     * network rather than about the media. A 404 does not improve because Wi-Fi arrived.
     */
    val networkFault: Boolean,
    /** Whether the player still wanted to play at the instant it failed. */
    val wantedToPlay: Boolean,
)

enum class ReconnectAction {
    /** Prepare again and carry on. */
    RESUME,

    /** Do nothing, and say in the diary why not. */
    WAIT,
}

/** An action, and the words to put in the diary next to it. */
data class ReconnectVerdict(val action: ReconnectAction, val reason: String)

/**
 * Whether a network coming back should start a book playing again.
 *
 * ExoPlayer's answer to a read that fails is to go idle and wait for somebody to prepare it
 * again. [PlaybackRetryPolicy] covers the case where the connection returns within seconds;
 * past that, nothing was watching, and a phone that regains signal after two minutes in a
 * lift stayed idle until the listener noticed. This is the other half of that.
 *
 * ## Why this is a class with tests rather than three lines in the service
 *
 * Getting it wrong is worse than not doing it. A book that starts playing in somebody's
 * pocket an hour after they paused it — out loud, on the phone's own speaker, because the
 * headphones went with the pause — is the behaviour people uninstall Spotify over, and it is
 * the thing lugu's settings screen already promises not to do in three other places. So
 * every condition that has to hold is named, ordered, and pinned by a test, and the reason
 * for each refusal is a string that ends up in the diary rather than a silent `return`.
 *
 * The conditions, strictest first:
 *
 *  - Something must actually be stalled. A [NetworkStall] is recorded only by a player error
 *    and is cleared by every transport command and by any successful playback, so a book the
 *    listener paused deliberately has no stall against it and can never be resumed by this.
 *    That clearing is the load-bearing half of the rule; the checks below are the safety net.
 *  - The stall must have been the network's fault. A missing file or an expired token fails
 *    identically on the new connection, and retrying it would turn a legible error into a
 *    loop.
 *  - The player must have wanted to play when it failed. A failure while paused — an armed
 *    book whose first buffering read failed, say — is not an interrupted listening session.
 *  - Something must still be loaded, or there is nothing to prepare.
 *  - It must have been recent. "The network came back" stops meaning "carry on" after long
 *    enough, and [WINDOW_MS] is deliberately the same half-hour that the service allows a
 *    headphone reconnection: the two answer the same question, and two different answers to
 *    one question is worse for the person reading the settings screen than either number.
 */
class ReconnectPolicy(private val windowMs: Long = WINDOW_MS) {

    fun verdictFor(
        stall: NetworkStall?,
        nowMs: Long,
        hasSomethingLoaded: Boolean,
        alreadyPlaying: Boolean,
    ): ReconnectVerdict {
        if (alreadyPlaying) return wait("already playing")
        if (stall == null) return wait("nothing was stopped by the network")
        if (!stall.networkFault) return wait("the stop was not the network's fault")
        if (!stall.wantedToPlay) return wait("nothing was playing when it stopped")
        if (!hasSomethingLoaded) return wait("nothing is loaded")

        val agoMs = nowMs - stall.atMs
        if (agoMs > windowMs || agoMs < 0) {
            return wait("the stop was ${agoMs / 60_000} min ago, past the window")
        }
        return ReconnectVerdict(ReconnectAction.RESUME, "resuming, stopped ${agoMs / 1_000}s ago")
    }

    private fun wait(reason: String) = ReconnectVerdict(ReconnectAction.WAIT, reason)

    companion object {
        /** How long after a network stall a returning connection still means "carry on". */
        const val WINDOW_MS = 30 * 60 * 1_000L
    }
}

/**
 * Watches for a network arriving.
 *
 * `registerDefaultNetworkCallback` rather than a `NetworkRequest`, because what matters is
 * the network the phone would actually use: a handover from a dying cell to Wi-Fi arrives
 * here as one `onAvailable` for the new default, which is exactly the moment a stalled read
 * could succeed. Registering also delivers whatever is already connected, which is harmless
 * — at the point this is registered nothing has stalled yet, and [ReconnectPolicy] refuses a
 * missing stall before it looks at anything else.
 *
 * Nothing is done with `onLost`. A network going away does not stop playback on its own —
 * the buffer keeps going for as long as it holds — and pre-emptively pausing on it would
 * turn a stall the buffer would have absorbed into an audible one.
 */
internal class NetworkWatcher(
    context: Context,
    private val onAvailable: () -> Unit,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val handler = Handler(Looper.getMainLooper())

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onAvailable()
        }
    }

    /**
     * Registering can throw when the process is already being torn down, and a playback
     * service that fails to start because of a connectivity callback would be a far worse
     * bug than the one this exists to fix.
     */
    fun start() {
        runCatching { connectivityManager?.registerDefaultNetworkCallback(callback, handler) }
    }

    fun stop() {
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
    }
}
