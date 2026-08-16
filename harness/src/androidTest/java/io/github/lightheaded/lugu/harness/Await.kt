package io.github.lightheaded.lugu.harness

import android.os.SystemClock

/**
 * Polling, written once.
 *
 * Everything this harness watches happens in another process and is observed by running a
 * command, so there is nothing to await on and no callback to register. Both helpers
 * return what they saw rather than a boolean, so a caller never has to look twice and get
 * a different answer the second time.
 */
internal object Await {

    const val POLL_MS = 500L

    /** The first non-null answer, or null if there was none before [timeoutMs]. */
    fun <T : Any> notNull(timeoutMs: Long, block: () -> T?): T? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            block()?.let { return it }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            SystemClock.sleep(POLL_MS)
        }
    }

    /** True as soon as [block] says so, false if it never did before [timeoutMs]. */
    fun until(timeoutMs: Long, block: () -> Boolean): Boolean =
        notNull(timeoutMs) { if (block()) true else null } ?: false
}
