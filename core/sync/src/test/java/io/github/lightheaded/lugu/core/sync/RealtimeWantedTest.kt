package io.github.lightheaded.lugu.core.sync

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A sign-in must bring the socket back at once.
 *
 * Every new value of [RealtimeWanted.of] starts a fresh supervisor with a fresh backoff, so
 * these tests pin what produces a new value. The case that broke: a sign-in to the account
 * that had lapsed writes an equal row, the account flow says nothing, and the socket sat out
 * the five-minute ceiling of the backoff it built up while the token was gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeWantedTest {

    private val account = MutableStateFlow<ActiveAccount?>(ALICE)
    private val lapsed = MutableStateFlow<ActiveAccount?>(null)
    private val foreground = MutableStateFlow(true)
    private val playing = MutableStateFlow(false)

    private fun wanted() = RealtimeWanted.of(account, lapsed, foreground, playing)

    @Test
    fun `a sign-in to the same account reconnects at once`() = runTest {
        wanted().test {
            assertThat(awaitItem()).isEqualTo(ALICE)

            lapsed.value = ALICE
            assertThat(awaitItem()).isNull()

            // Only the lapse ends. The account flow does not emit again, as with a real
            // sign-in that writes the same row back.
            val signedInAt = currentTime
            lapsed.value = null
            assertThat(awaitItem()).isEqualTo(ALICE)
            assertThat(currentTime).isEqualTo(signedInAt)
        }
    }

    @Test
    fun `a lapsed account opens no socket`() = runTest {
        lapsed.value = ALICE
        wanted().test {
            assertThat(awaitItem()).isNull()
            expectNoEvents()
        }
    }

    /** A lapse is not a flap: the socket is given up at once, with no grace period. */
    @Test
    fun `a lapse gives the socket up at once`() = runTest {
        wanted().test {
            assertThat(awaitItem()).isEqualTo(ALICE)
            val lapsedAt = currentTime
            lapsed.value = ALICE
            assertThat(awaitItem()).isNull()
            assertThat(currentTime).isEqualTo(lapsedAt)
        }
    }

    /**
     * A sign-in inside what would have been the grace period. Held for it, the lapse and the
     * sign-in collapsed to no change, and the old supervisor kept its backoff.
     */
    @Test
    fun `a sign-in soon after a lapse still starts a new supervisor`() = runTest {
        wanted().test {
            assertThat(awaitItem()).isEqualTo(ALICE)
            lapsed.value = ALICE
            // The sign-in comes well inside the grace period of leaving the screen.
            testScheduler.advanceTimeBy(RealtimeWanted.BACKGROUND_GRACE_MS / 10)
            lapsed.value = null
            assertThat(awaitItem()).isNull()
            assertThat(awaitItem()).isEqualTo(ALICE)
            expectNoEvents()
        }
    }

    /** Leaving the screen still waits out the grace period, and coming back inside it is no change. */
    @Test
    fun `leaving the screen gives the socket up after the grace period`() = runTest {
        wanted().test {
            assertThat(awaitItem()).isEqualTo(ALICE)
            foreground.value = false
            testScheduler.advanceTimeBy(RealtimeWanted.BACKGROUND_GRACE_MS / 2)
            foreground.value = true
            expectNoEvents()

            val leftAt = currentTime
            foreground.value = false
            assertThat(awaitItem()).isNull()
            assertThat(currentTime - leftAt).isEqualTo(RealtimeWanted.BACKGROUND_GRACE_MS)
        }
    }

    @Test
    fun `a signed-in account off screen and not playing opens no socket`() = runTest {
        foreground.value = false
        wanted().test {
            assertThat(awaitItem()).isNull()
            playing.value = true
            assertThat(awaitItem()).isEqualTo(ALICE)
        }
    }

    private companion object {
        val ALICE = ActiveAccount(
            serverId = "https://abs.example#u1",
            baseUrl = "https://abs.example",
            userId = "u1",
            username = "alice",
            defaultLibraryId = null,
        )
    }
}
