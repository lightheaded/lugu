package io.github.lightheaded.lugu.core.api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Chooses between the address the account was created with and a second address on the
 * listener's own network.
 *
 * The choice is made by trying, never by inspecting the network. Reading the name of the
 * current Wi-Fi network needs the location permission on Android 10 and later, and asking
 * somebody for their location so that a book loads faster is not a trade worth offering.
 * So the local address is simply attempted, with a short deadline, and the answer is
 * remembered for a while — a race on every request would cost more than the reverse proxy
 * it is meant to avoid.
 *
 * The deadline is the part that matters most. An address that no longer resolves fails
 * fast and is unremarkable; the dangerous case is one that resolves to an address nothing
 * answers on, which hangs until the operating system gives up minutes later. Bounding the
 * whole attempt means the worst case is [timeoutMs] added to one request per
 * [rememberForMs], and never a book that will not start.
 *
 * Switching addresses is only safe because progress is keyed by server id and user id,
 * and the server id is derived once, at sign-in, from the address that was typed — it is
 * never re-derived from whichever address won a race. Upstream keys progress by
 * connection instead, which is why a second address there can silently split one book's
 * history in two (audiobookshelf-app#1401); nothing in this codebase can do that.
 */
class ConnectionRace(
    /**
     * Takes the headers as well as the address, because the probe has to be the same
     * request the real traffic will be: an address that only answers when it is given a
     * proxy header would otherwise be declared dead and never used.
     */
    private val probe: suspend (String, List<ConnectionHeader>) -> Boolean,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val rememberForMs: Long = DEFAULT_MEMORY_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class Decision(
        val primary: String,
        val lan: String,
        val lanWon: Boolean,
        val atMs: Long,
    )

    private val mutex = Mutex()

    @Volatile private var decision: Decision? = null

    /**
     * The address to use now. Falls back to [primary] whenever there is no second address,
     * the probe says no, or the probe does not answer in time.
     */
    suspend fun preferred(
        primary: String,
        lan: String?,
        headers: List<ConnectionHeader> = emptyList(),
    ): String {
        if (lan.isNullOrBlank() || lan == primary) return primary

        remembered(primary, lan)?.let { return if (it) lan else primary }

        // Single-flight: on a cold start a dozen requests arrive at once, and they should
        // wait for one answer rather than each start their own race.
        return mutex.withLock {
            remembered(primary, lan)?.let { return@withLock if (it) lan else primary }

            val lanWon = withTimeoutOrNull(timeoutMs) {
                runCatching { probe(lan, headers) }.getOrDefault(false)
            } == true

            decision = Decision(primary, lan, lanWon, nowMs())
            if (lanWon) lan else primary
        }
    }

    /**
     * Forgets the last answer, so the next request races again. Called when the addresses
     * are edited or when somebody presses the test button, both of which mean the previous
     * answer was about a different question.
     */
    fun forget() {
        decision = null
    }

    private fun remembered(primary: String, lan: String): Boolean? = decision
        ?.takeIf { it.primary == primary && it.lan == lan }
        ?.takeIf { nowMs() - it.atMs in 0 until rememberForMs }
        ?.lanWon

    companion object {
        /**
         * Long enough for a round trip to a machine in the same building, short enough that
         * a listener who has left the house does not notice the fallback.
         */
        const val DEFAULT_TIMEOUT_MS = 600L

        /**
         * Both answers are remembered for the same span, which is a compromise. Shorter and
         * arriving home switches to the fast address sooner; longer and leaving the house
         * costs fewer of those timeouts. A minute makes both barely noticeable.
         */
        const val DEFAULT_MEMORY_MS = 60_000L
    }
}
