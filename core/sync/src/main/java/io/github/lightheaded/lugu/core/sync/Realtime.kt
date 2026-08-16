package io.github.lightheaded.lugu.core.sync

import io.github.lightheaded.lugu.core.api.AbsClient
import io.github.lightheaded.lugu.core.sync.di.RealtimeHttp
import io.socket.client.IO
import io.socket.client.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient

/**
 * A change the server pushed, reduced to what lugu actually acts on.
 *
 * Deliberately small and deliberately id-only. The Socket.IO payloads are large,
 * partially populated and version-dependent; treating one as a row to write is how a
 * good record gets replaced by a half-populated one. Everything here is a *hint* that
 * something is stale, and the mirror is refreshed from the API afterwards.
 */
sealed interface RealtimeEvent {
    /** These items were added or edited somewhere else. */
    data class ItemsChanged(val itemIds: List<String>) : RealtimeEvent

    /** These items no longer exist on the server. */
    data class ItemsRemoved(val itemIds: List<String>) : RealtimeEvent

    /** A library was added, renamed, reordered or deleted. */
    data object LibrariesChanged : RealtimeEvent

    /** Progress for one item — and one episode, for a podcast — moved on another device. */
    data class ProgressChanged(val libraryItemId: String, val episodeId: String?) : RealtimeEvent

    /**
     * The user object was replaced wholesale. It carries every progress row, but which
     * of them actually moved is not stated, so this only means "progress may have
     * changed anywhere".
     */
    data object UserChanged : RealtimeEvent
}

/**
 * The wire names, in one place.
 *
 * Getting one of these wrong produces a feature that compiles, connects, and silently
 * never fires — the worst failure available here — so they are pinned as constants and
 * covered by a test rather than spelled inline at each call site.
 *
 * Confirmed against the Audiobookshelf server source on `advplyr/audiobookshelf@master`:
 * the handshake and the emitters in `server/SocketAuthority.js`, the item events in
 * `server/controllers/LibraryItemController.js` and `server/scanner/LibraryScanner.js`,
 * the removal in `server/routers/ApiRouter.js`, the library events in
 * `server/controllers/LibraryController.js`, and the progress event in
 * `server/managers/PlaybackSessionManager.js`. There is deliberately no batch removal
 * event in the list: the server has none, and emits one `item_removed` per item.
 */
internal object RealtimeNames {
    /** Client to server: the access token, emitted immediately after every connect. */
    const val AUTH = "auth"

    /** Server to client: authentication accepted. Carries the user id and name, nothing more. */
    const val INIT = "init"

    /** Server to client: authentication rejected. Not retried on the same socket. */
    const val AUTH_FAILED = "auth_failed"

    const val ITEM_ADDED = "item_added"
    const val ITEM_UPDATED = "item_updated"
    const val ITEM_REMOVED = "item_removed"
    const val ITEMS_ADDED = "items_added"
    const val ITEMS_UPDATED = "items_updated"
    const val LIBRARY_ADDED = "library_added"
    const val LIBRARY_UPDATED = "library_updated"
    const val LIBRARY_REMOVED = "library_removed"
    const val USER_UPDATED = "user_updated"
    const val USER_ITEM_PROGRESS_UPDATED = "user_item_progress_updated"

    /** Everything subscribed to. Anything not listed here is ignored by construction. */
    val SUBSCRIBED = listOf(
        ITEM_ADDED,
        ITEM_UPDATED,
        ITEM_REMOVED,
        ITEMS_ADDED,
        ITEMS_UPDATED,
        LIBRARY_ADDED,
        LIBRARY_UPDATED,
        LIBRARY_REMOVED,
        USER_UPDATED,
        USER_ITEM_PROGRESS_UPDATED,
    )
}

/**
 * Turns one raw Socket.IO frame into a [RealtimeEvent], or into nothing.
 *
 * Pure and string-in, so the whole of it is testable without a socket, without
 * `org.json` and without a device. Every shape it does not recognise produces null
 * rather than an exception: a server a version ahead of this client will send fields
 * that are not here, and that is not an error.
 */
internal object RealtimeEvents {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(name: String, payload: String?): RealtimeEvent? = when (name) {
        RealtimeNames.ITEM_ADDED,
        RealtimeNames.ITEM_UPDATED,
        RealtimeNames.ITEMS_ADDED,
        RealtimeNames.ITEMS_UPDATED,
        -> itemIds(payload).takeIf { it.isNotEmpty() }?.let { RealtimeEvent.ItemsChanged(it) }

        RealtimeNames.ITEM_REMOVED ->
            itemIds(payload).takeIf { it.isNotEmpty() }?.let { RealtimeEvent.ItemsRemoved(it) }

        RealtimeNames.LIBRARY_ADDED,
        RealtimeNames.LIBRARY_UPDATED,
        RealtimeNames.LIBRARY_REMOVED,
        -> RealtimeEvent.LibrariesChanged

        RealtimeNames.USER_UPDATED -> RealtimeEvent.UserChanged

        RealtimeNames.USER_ITEM_PROGRESS_UPDATED -> progress(payload)

        else -> null
    }

    /**
     * Every library item id the payload mentions.
     *
     * The singular events carry one item object, the plural ones an array of them, and
     * the removal event has at times carried nothing but an id string. All three are
     * accepted because the cost of accepting a shape the server does not send is zero
     * and the cost of rejecting one it does is a feature that never fires.
     */
    private fun itemIds(payload: String?): List<String> {
        val root = element(payload)
            ?: return listOfNotNull(payload?.trim()?.takeIf { it.isPlausibleId() })
        return when (root) {
            is JsonArray -> root.mapNotNull { itemId(it) }
            else -> listOfNotNull(itemId(root))
        }.distinct()
    }

    private fun itemId(element: JsonElement): String? {
        (element as? JsonPrimitive)?.let { return it.content.takeIf { id -> id.isPlausibleId() } }
        val obj = element as? JsonObject ?: return null
        // `libraryItem` is how the episode and progress payloads nest the item they
        // belong to; `id` is the item itself.
        return obj.string("id")
            ?: (obj["libraryItem"] as? JsonObject)?.string("id")
            ?: obj.string("libraryItemId")
    }

    /**
     * The progress payload names its item directly, which is all that is taken from it —
     * the position itself is re-read from the API so it goes through the same conflict
     * resolution as every other progress change.
     */
    private fun progress(payload: String?): RealtimeEvent? {
        val obj = element(payload) as? JsonObject ?: return null
        val data = obj["data"] as? JsonObject
        val itemId = obj.string("libraryItemId") ?: data?.string("libraryItemId") ?: return null
        val episodeId = obj.string("episodeId") ?: data?.string("episodeId")
        return RealtimeEvent.ProgressChanged(itemId, episodeId)
    }

    private fun element(payload: String?): JsonElement? {
        val text = payload?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return null
        return runCatching { json.parseToJsonElement(text) }.getOrNull()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isPlausibleId() }

    /** Guards against an empty string, a JSON `null` or a whole object being read as an id. */
    private fun String.isPlausibleId(): Boolean =
        isNotBlank() && this != "null" && !startsWith("{") && !startsWith("[")
}

/**
 * How long to wait before trying the socket again.
 *
 * Exponential with a five-minute ceiling and a jitter band. The ceiling matters more
 * than the growth rate: an unreachable server, or one behind a proxy that will never
 * pass an upgrade, is the normal steady state for some installs, and a client that
 * keeps knocking every two seconds forever is a battery drain with nothing to show for
 * it. The jitter is so a server coming back up is not met by every client at once.
 */
internal object RealtimeBackoff {
    const val FIRST_DELAY_MS = 2_000L
    const val MAX_DELAY_MS = 5 * 60 * 1000L

    /** Fraction either side of the base delay that the jitter can move it. */
    private const val JITTER = 0.2

    /** Beyond this the delay is already at the ceiling, so shifting further only risks overflow. */
    private const val MAX_STEP = 20

    fun delayMs(failures: Int, jitter: Double = Random.nextDouble()): Long {
        val base = (FIRST_DELAY_MS shl failures.coerceIn(0, MAX_STEP)).coerceAtMost(MAX_DELAY_MS)
        val factor = 1.0 - JITTER + 2 * JITTER * jitter.coerceIn(0.0, 1.0)
        return (base * factor).toLong().coerceAtLeast(1L)
    }
}

/**
 * Splits a server address into the origin Socket.IO connects to and the path it lives
 * under.
 *
 * Audiobookshelf is commonly reverse-proxied onto a subpath, and Socket.IO takes the
 * subpath as its `path` option rather than as part of the URI. Getting this wrong is
 * indistinguishable from the server being down, so it is pulled out and tested.
 */
internal object RealtimeUrl {
    private const val ENDPOINT = "/socket.io"

    fun originOf(baseUrl: String): String {
        val scheme = baseUrl.substringBefore("://")
        val authority = baseUrl.substringAfter("://").substringBefore('/')
        return "$scheme://$authority"
    }

    fun pathOf(baseUrl: String): String {
        val subpath = baseUrl.substringAfter("://").substringAfter('/', "").trim('/')
        return if (subpath.isEmpty()) ENDPOINT else "/$subpath$ENDPOINT"
    }
}

/**
 * The live connection to one server, and nothing else.
 *
 * This exists because the mirror was poll-and-sweep only: an item edited on the web, or
 * a position moved on another device, waited until the next full sync. It is an
 * optimisation on top of that sync, never a replacement for it — which is the licence
 * to fail silently. Nothing in here reports an error to the user, retries aggressively,
 * or throws: a server behind a proxy that will not pass WebSockets, an expired token, a
 * captive portal and a flat network are all ordinary, and the app is fully correct
 * without any of this working.
 *
 * The connection is held only while it is worth holding — see [setForeground] and
 * [setPlaybackActive]. A socket kept open in the background costs radio wakeups and
 * delivers events nobody is there to see.
 *
 * Socket.IO's own reconnection is switched off in favour of [RealtimeBackoff]. The
 * client has to fetch a fresh access token and re-emit `auth` on every attempt anyway,
 * so owning the loop is both simpler to reason about and the only way the delay between
 * attempts is testable.
 */
@Singleton
class Realtime @Inject constructor(
    private val authRepository: AuthRepository,
    private val client: AbsClient,
    @RealtimeHttp private val http: OkHttpClient,
) {
    private val _events = MutableSharedFlow<RealtimeEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        // A slow consumer must not be able to stall the socket thread, and a stale
        // hint is worthless anyway — the sweep is the backstop for anything dropped.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Hints that something in the mirror is stale. Never completes, never throws. */
    val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    private val _connected = MutableStateFlow(false)

    /** Whether the server is currently pushing to us. For diagnostics, not for correctness. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val foreground = MutableStateFlow(false)
    private val playing = MutableStateFlow(false)
    private val started = AtomicBoolean(false)

    /** The app is on screen. */
    fun setForeground(value: Boolean) {
        foreground.value = value
    }

    /** Something is loaded in the player, so a position moved elsewhere matters right now. */
    fun setPlaybackActive(value: Boolean) {
        playing.value = value
    }

    /**
     * Starts watching for a reason to connect. Safe to call more than once; only the
     * first call does anything.
     */
    // The per-value debounce is what makes the grace period one-sided: connecting is
    // immediate and disconnecting waits. The fixed-delay overload is stable but would
    // delay both, which would mean ten seconds of nothing every time the app is opened.
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(authRepository.observeAccount(), foreground, playing) { account, onScreen, loaded ->
                account.takeIf { onScreen || loaded }
            }
                // A rotation, or a glance at another app, must not tear the socket down
                // and build it back up. Debouncing before distinctUntilChanged means a
                // flap inside the grace period collapses to no change at all.
                .debounce { wanted -> if (wanted == null) BACKGROUND_GRACE_MS else 0L }
                .distinctUntilChanged()
                .collectLatest { account -> if (account != null) supervise(account) }
        }
    }

    /** Reconnects for as long as this account is the one we want, backing off on failure. */
    private suspend fun supervise(account: ActiveAccount) {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val authenticated = try {
                connectOnce(account)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Includes the socket library throwing from a callback thread. There is
                // no failure here worth surfacing: the sweep still has the data covered.
                false
            }
            failures = if (authenticated) 0 else failures + 1
            delay(RealtimeBackoff.delayMs(failures))
        }
    }

    /**
     * One connection attempt, from open to close.
     *
     * Returns true when the server accepted our token at least once, which is what
     * resets the backoff — a socket that connects and is immediately dropped because
     * the token was refused is a failure, not a success, and must not reset it.
     */
    private suspend fun connectOnce(account: ActiveAccount): Boolean = coroutineScope {
        // Nothing to authenticate with, so there is nothing worth opening. Checked here
        // rather than after connecting so a signed-out app does not keep handshaking.
        if (runCatching { client.validAccessToken() }.isFailure) return@coroutineScope false

        val options = IO.Options().apply {
            path = RealtimeUrl.pathOf(account.baseUrl)
            // Transports are left at the default (long-poll, upgrading to WebSocket)
            // rather than forced to WebSocket. A proxy that will not pass an upgrade is
            // common, and polling still delivers every event; forcing the upgrade would
            // turn a working setup into a permanently dead one.
            reconnection = false
            forceNew = true
            timeout = CONNECT_TIMEOUT_MS
            callFactory = http
            webSocketFactory = http
        }

        val socket = runCatching { IO.socket(URI.create(RealtimeUrl.originOf(account.baseUrl)), options) }
            .getOrNull() ?: return@coroutineScope false

        val ended = CompletableDeferred<Unit>()
        val authenticated = AtomicBoolean(false)

        // Asking for a token suspends, and a Socket.IO listener runs on the library's
        // own thread, so the callback only rings this bell and a coroutine answers it.
        val reauthNeeded = Channel<Unit>(Channel.CONFLATED)

        socket.on(Socket.EVENT_CONNECT) { reauthNeeded.trySend(Unit) }
        socket.on(RealtimeNames.INIT) {
            authenticated.set(true)
            _connected.value = true
        }
        socket.on(RealtimeNames.AUTH_FAILED) {
            // Nothing to retry on this socket; the loop will come back with a fresh
            // token, and if that is refused too it backs off like any other failure.
            ended.complete(Unit)
        }
        socket.on(Socket.EVENT_DISCONNECT) { ended.complete(Unit) }
        socket.on(Socket.EVENT_CONNECT_ERROR) { ended.complete(Unit) }

        for (name in RealtimeNames.SUBSCRIBED) {
            socket.on(name) { args ->
                runCatching { RealtimeEvents.parse(name, args.firstOrNull()?.toString()) }
                    .getOrNull()
                    ?.let { _events.tryEmit(it) }
            }
        }

        // The first `auth` and every later one are the same emit, deliberately: the
        // server re-associates the socket with the user and replies with `init` again,
        // whether it is the first time or the tenth.
        val authenticator = launch {
            for (unused in reauthNeeded) {
                val fresh = runCatching { client.validAccessToken() }.getOrNull() ?: continue
                runCatching { socket.emit(RealtimeNames.AUTH, fresh) }
            }
        }

        // Access tokens last about an hour, and the server never re-checks one it has
        // already accepted. A socket left alone therefore outlives its token, stays
        // open, and goes quiet — which is indistinguishable from a server with nothing
        // to say. Renewing well inside the lifetime is what stops that.
        val renewal = launch {
            while (isActive) {
                delay(REAUTH_INTERVAL_MS)
                reauthNeeded.trySend(Unit)
            }
        }

        try {
            socket.connect()
            ended.await()
        } finally {
            renewal.cancel()
            authenticator.cancel()
            reauthNeeded.close()
            _connected.value = false
            runCatching { socket.off() }
            runCatching { socket.close() }
        }
        authenticated.get()
    }

    private companion object {
        const val EVENT_BUFFER = 64
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val REAUTH_INTERVAL_MS = 15 * 60 * 1000L

        /** How long the app has to come back before the socket is given up. */
        const val BACKGROUND_GRACE_MS = 10_000L
    }
}
