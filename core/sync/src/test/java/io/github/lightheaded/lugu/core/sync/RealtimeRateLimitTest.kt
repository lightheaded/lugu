package io.github.lightheaded.lugu.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Somebody else's library scan must not become a request storm from a phone. Past the
 * ceiling the hints are dropped, which costs nothing: the sweep covers the same ground
 * and is the better tool once that much has changed.
 */
class RealtimeRateLimitTest {

    @Test
    fun `it allows up to the ceiling`() {
        val limit = RealtimeRateLimit(maxPerWindow = 3, windowMs = 1_000)
        assertThat(limit.allow(0)).isTrue()
        assertThat(limit.allow(0)).isTrue()
        assertThat(limit.allow(0)).isTrue()
        assertThat(limit.allow(0)).isFalse()
    }

    @Test
    fun `it opens again once the window has passed`() {
        val limit = RealtimeRateLimit(maxPerWindow = 2, windowMs = 1_000)
        assertThat(limit.allow(0)).isTrue()
        assertThat(limit.allow(500)).isTrue()
        assertThat(limit.allow(999)).isFalse()
        assertThat(limit.allow(1_000)).isTrue()
    }

    /** A long quiet spell must not bank up credit that lets a later burst through. */
    @Test
    fun `it does not accumulate allowance while idle`() {
        val limit = RealtimeRateLimit(maxPerWindow = 2, windowMs = 1_000)
        assertThat(limit.allow(0)).isTrue()
        assertThat(limit.allow(600_000)).isTrue()
        assertThat(limit.allow(600_000)).isTrue()
        assertThat(limit.allow(600_000)).isFalse()
    }
}
