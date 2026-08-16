package io.github.lightheaded.lugu.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A server that will never answer is a normal, permanent state for some installs — a
 * reverse proxy that does not pass an upgrade, a self-hosted box that is off overnight.
 * The socket has to keep failing without becoming a background battery cost, which is
 * what the ceiling is for.
 */
class RealtimeBackoffTest {

    @Test
    fun `the first retry is quick`() {
        assertThat(RealtimeBackoff.delayMs(failures = 0, jitter = MID))
            .isEqualTo(RealtimeBackoff.FIRST_DELAY_MS)
    }

    @Test
    fun `each failure doubles the wait`() {
        assertThat(RealtimeBackoff.delayMs(1, MID)).isEqualTo(RealtimeBackoff.FIRST_DELAY_MS * 2)
        assertThat(RealtimeBackoff.delayMs(2, MID)).isEqualTo(RealtimeBackoff.FIRST_DELAY_MS * 4)
        assertThat(RealtimeBackoff.delayMs(3, MID)).isEqualTo(RealtimeBackoff.FIRST_DELAY_MS * 8)
    }

    @Test
    fun `it stops growing at the ceiling`() {
        for (failures in 8..1_000) {
            assertThat(RealtimeBackoff.delayMs(failures, MID)).isEqualTo(RealtimeBackoff.MAX_DELAY_MS)
        }
    }

    /** Every client of a server that just came back must not knock at the same instant. */
    @Test
    fun `the delay is spread either side of the base`() {
        val low = RealtimeBackoff.delayMs(4, jitter = 0.0)
        val high = RealtimeBackoff.delayMs(4, jitter = 1.0)
        assertThat(low).isLessThan(RealtimeBackoff.delayMs(4, MID))
        assertThat(high).isGreaterThan(RealtimeBackoff.delayMs(4, MID))
    }

    @Test
    fun `it never returns zero, whatever it is asked`() {
        for (failures in -5..40) {
            for (jitter in listOf(-1.0, 0.0, 0.5, 1.0, 2.0)) {
                assertThat(RealtimeBackoff.delayMs(failures, jitter)).isAtLeast(1L)
            }
        }
    }

    private companion object {
        /** No jitter either way, so the base delay is what is being asserted. */
        const val MID = 0.5
    }
}
