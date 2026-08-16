package io.github.lightheaded.lugu.core.api

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What happened when an address was tried, in terms a screen can put in a sentence. */
sealed interface ProbeOutcome {
    /** An Audiobookshelf server answered. */
    data class Answered(val serverVersion: String?) : ProbeOutcome

    /** Something answered, but it was not Audiobookshelf — usually a proxy or a router page. */
    data object NotAudiobookshelf : ProbeOutcome

    /** Nothing answered in time. [reason] is for the person looking at the screen, nowhere else. */
    data class Silent(val reason: String) : ProbeOutcome
}

/**
 * Asks an address whether an Audiobookshelf server is behind it.
 *
 * Used for two things: deciding the local-network race, and answering the "test this now"
 * button honestly. Both need the same check, because a probe that is happy with any TCP
 * connection would pick a router's login page over the real server and produce a library
 * that will not load.
 *
 * Its own client, with its own timeouts, on purpose: the shared client is tuned for
 * streaming a forty-hour book, and those timeouts are the opposite of what a race needs.
 * The client certificate is applied, since a server behind mTLS refuses this handshake
 * exactly as it refuses every other one.
 */
class ConnectionProbe(
    private val timeoutMs: Long = ConnectionRace.DEFAULT_TIMEOUT_MS,
    private val client: OkHttpClient = ConnectionKeyMaterial.applyTo(OkHttpClient.Builder())
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        // The hard stop. Without it a connection that is accepted and then ignored keeps
        // the caller waiting well past the race deadline, since the blocking call has no
        // suspension point for cancellation to land on.
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build(),
) {

    suspend fun probe(baseUrl: String, headers: List<ConnectionHeader> = emptyList()): ProbeOutcome =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/status")
                .apply { headers.forEach { header(it.name, it.value) } }
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext ProbeOutcome.NotAudiobookshelf
                    // Peeked rather than read: a proxy error page can be a megabyte, and
                    // the identifying field is in the first line of a real answer.
                    val body = response.peekBody(BODY_PEEK_BYTES).string()
                    if (!body.contains(APP_MARKER)) {
                        ProbeOutcome.NotAudiobookshelf
                    } else {
                        ProbeOutcome.Answered(VERSION.find(body)?.groupValues?.get(1))
                    }
                }
            } catch (e: Exception) {
                // The message can name the host, which is fine on the screen the person
                // typed it into and nowhere else. It is never attached to feedback.
                ProbeOutcome.Silent(e.message ?: "no answer")
            }
        }

    /** The shape the race wants: reachable or not. */
    suspend fun reachable(baseUrl: String, headers: List<ConnectionHeader> = emptyList()): Boolean =
        probe(baseUrl, headers) is ProbeOutcome.Answered

    private companion object {
        const val APP_MARKER = "audiobookshelf"
        const val BODY_PEEK_BYTES = 2048L
        val VERSION = Regex(""""serverVersion"\s*:\s*"([^"]+)"""")
    }
}
