package io.github.lightheaded.lugu.feature.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What the podcast page says after it asks the server for new feed episodes.
 *
 * The empty answer is the case worth a test of its own. It is the commonest answer, and it
 * must read as a fact about the feed rather than as a failed request.
 */
class ServerFetchLineTest {

    @Test
    fun `an empty answer is a fact about the feed and not a failure`() {
        assertThat(serverFetchLine(0)).isEqualTo("The feed has no new episodes.")
    }

    @Test
    fun `one new episode is counted in the singular`() {
        assertThat(serverFetchLine(1)).isEqualTo("The server now fetches 1 new episode.")
    }

    @Test
    fun `more than one new episode is counted`() {
        assertThat(serverFetchLine(4)).isEqualTo("The server now fetches 4 new episodes.")
    }
}
