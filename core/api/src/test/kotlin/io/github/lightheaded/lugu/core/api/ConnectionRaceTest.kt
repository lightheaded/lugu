package io.github.lightheaded.lugu.core.api

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The second address is only worth having if choosing it is cheap and getting it wrong is
 * cheaper. These tests are about the second half: an address that resolves and then hangs
 * is the realistic failure — a stale DHCP lease, a machine that is asleep — and it must
 * cost one short timeout rather than a book that will not start.
 */
class ConnectionRaceTest {

    private var clock = 0L

    private fun race(
        timeoutMs: Long = 600,
        rememberForMs: Long = 60_000,
        probe: suspend (String, List<ConnectionHeader>) -> Boolean,
    ) = ConnectionRace(
        probe = probe,
        timeoutMs = timeoutMs,
        rememberForMs = rememberForMs,
        nowMs = { clock },
    )

    @Test
    fun `the local address is used when it answers`() = runTest {
        val subject = race { address, _ -> address == LAN }

        assertThat(subject.preferred(SERVER, LAN)).isEqualTo(LAN)
    }

    @Test
    fun `the usual address is used when the local one says no`() = runTest {
        val subject = race { _, _ -> false }

        assertThat(subject.preferred(SERVER, LAN)).isEqualTo(SERVER)
    }

    /**
     * The important one. A hanging address must cost the deadline and nothing more; before
     * this, the operating system's own connect timeout would have stalled playback for
     * minutes.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `an address that hangs costs the deadline and then falls back`() = runTest {
        val subject = race(timeoutMs = 600) { _, _ ->
            delay(Long.MAX_VALUE / 2)
            true
        }

        val startedAt = currentTime
        val chosen = subject.preferred(SERVER, LAN)

        assertThat(chosen).isEqualTo(SERVER)
        assertThat(currentTime - startedAt).isEqualTo(600)
    }

    @Test
    fun `a probe that throws is a fallback rather than a crash`() = runTest {
        val subject = race { _, _ -> error("no route to host") }

        assertThat(subject.preferred(SERVER, LAN)).isEqualTo(SERVER)
    }

    @Test
    fun `no second address means no race at all`() = runTest {
        val probes = AtomicInteger(0)
        val subject = race { _, _ ->
            probes.incrementAndGet()
            true
        }

        assertThat(subject.preferred(SERVER, null)).isEqualTo(SERVER)
        assertThat(subject.preferred(SERVER, "")).isEqualTo(SERVER)
        assertThat(subject.preferred(SERVER, SERVER)).isEqualTo(SERVER)
        assertThat(probes.get()).isEqualTo(0)
    }

    @Test
    fun `the answer is remembered, so every request is not a race`() = runTest {
        val probes = AtomicInteger(0)
        val subject = race { _, _ ->
            probes.incrementAndGet()
            true
        }

        repeat(20) { assertThat(subject.preferred(SERVER, LAN)).isEqualTo(LAN) }

        assertThat(probes.get()).isEqualTo(1)
    }

    @Test
    fun `the answer is asked again once it is stale`() = runTest {
        val probes = AtomicInteger(0)
        val subject = race(rememberForMs = 60_000) { _, _ ->
            probes.incrementAndGet()
            true
        }

        subject.preferred(SERVER, LAN)
        clock += 59_000
        subject.preferred(SERVER, LAN)
        clock += 2_000
        subject.preferred(SERVER, LAN)

        assertThat(probes.get()).isEqualTo(2)
    }

    /** A burst of requests on a cold start should wait for one answer, not start twenty races. */
    @Test
    fun `parallel callers produce one probe`() = runTest {
        val probes = AtomicInteger(0)
        val subject = race { _, _ ->
            probes.incrementAndGet()
            delay(100)
            true
        }

        val chosen = (1..20).map { async { subject.preferred(SERVER, LAN) } }.awaitAll()

        assertThat(chosen.toSet()).containsExactly(LAN)
        assertThat(probes.get()).isEqualTo(1)
    }

    @Test
    fun `editing the addresses throws the remembered answer away`() = runTest {
        val probes = AtomicInteger(0)
        val subject = race { _, _ ->
            probes.incrementAndGet()
            true
        }

        subject.preferred(SERVER, LAN)
        subject.forget()
        subject.preferred(SERVER, LAN)
        // A different second address is a different question, so it is not remembered either.
        subject.preferred(SERVER, "http://192.168.1.99:13378")

        assertThat(probes.get()).isEqualTo(3)
    }

    /**
     * The probe has to be the same request the real traffic will be. An address that only
     * answers when it is given a proxy header would otherwise be declared dead.
     */
    @Test
    fun `the probe is given the headers the real requests will carry`() = runTest {
        var seen: List<ConnectionHeader> = emptyList()
        val subject = race { _, headers ->
            seen = headers
            true
        }

        subject.preferred(SERVER, LAN, listOf(ConnectionHeader("CF-Access-Client-Id", "id")))

        assertThat(seen.map { it.name }).containsExactly("CF-Access-Client-Id")
    }

    private companion object {
        const val SERVER = "https://books.example"
        const val LAN = "http://192.168.1.10:13378"
    }
}
