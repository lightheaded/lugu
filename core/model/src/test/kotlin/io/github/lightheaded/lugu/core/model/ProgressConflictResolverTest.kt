package io.github.lightheaded.lugu.core.model

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

class ProgressConflictResolverTest {

    private fun progress(time: Double, lastUpdate: Long, finished: Boolean = false) =
        MediaProgress(
            libraryItemId = "li_1",
            currentTimeSec = time,
            durationSec = 3600.0,
            progress = time / 3600.0,
            isFinished = finished,
            lastUpdateMs = lastUpdate,
        )

    @Test
    fun `adopts newer server position`() {
        val result = ProgressConflictResolver.resolve(
            local = progress(100.0, lastUpdate = 1_000),
            server = progress(900.0, lastUpdate = 2_000),
        )

        assertThat(result).isInstanceOf(ProgressResolution.AdoptServer::class.java)
        val adopt = result as ProgressResolution.AdoptServer
        assertThat(adopt.server.currentTimeSec).isEqualTo(900.0)
        assertThat(adopt.jumpSeconds).isEqualTo(800.0)
        assertThat(adopt.replacedLocal?.currentTimeSec).isEqualTo(100.0)
    }

    @Test
    fun `keeps local when local update is newer`() {
        val result = ProgressConflictResolver.resolve(
            local = progress(900.0, lastUpdate = 5_000),
            server = progress(100.0, lastUpdate = 2_000),
        )

        assertThat(result).isInstanceOf(ProgressResolution.KeepLocal::class.java)
    }

    @Test
    fun `small drift is not a conflict`() {
        val result = ProgressConflictResolver.resolve(
            local = progress(100.0, lastUpdate = 1_000),
            server = progress(103.0, lastUpdate = 9_000),
        )

        assertThat(result).isEqualTo(ProgressResolution.InSync)
    }

    @Test
    fun `finished flag difference is always a conflict even at the same position`() {
        val result = ProgressConflictResolver.resolve(
            local = progress(3_600.0, lastUpdate = 1_000, finished = false),
            server = progress(3_600.0, lastUpdate = 2_000, finished = true),
        )

        assertThat(result).isInstanceOf(ProgressResolution.AdoptServer::class.java)
    }

    @Test
    fun `no server progress means local is authoritative`() {
        val result = ProgressConflictResolver.resolve(local = progress(42.0, 1), server = null)
        assertThat(result).isInstanceOf(ProgressResolution.KeepLocal::class.java)
    }

    @Test
    fun `first play on this device adopts whatever the server has`() {
        val result = ProgressConflictResolver.resolve(local = null, server = progress(42.0, 1))
        assertThat(result).isInstanceOf(ProgressResolution.AdoptServer::class.java)
    }

    /**
     * The invariant lugu exists to protect: no automatic push may ever move the
     * server backwards in update time. Checked over random histories rather than
     * hand-picked cases.
     */
    @Test
    fun `automatic pushes never regress the server`() {
        val random = Random(seed = 20260814)
        repeat(2_000) {
            val serverUpdate = random.nextLong(0, 1_000_000)
            val localUpdate = random.nextLong(0, 1_000_000)
            val local = progress(random.nextDouble(0.0, 3_600.0), localUpdate)
            val server = progress(random.nextDouble(0.0, 3_600.0), serverUpdate)

            val allowed = ProgressConflictResolver.mayPushAutomatically(local, server)

            if (allowed) {
                assertThat(local.lastUpdateMs).isAtLeast(server.lastUpdateMs)
            }
        }
    }

    /** Resolution must be stable: resolving twice cannot flip the decision. */
    @Test
    fun `resolution is idempotent`() {
        val random = Random(seed = 7)
        repeat(1_000) {
            val local = progress(random.nextDouble(0.0, 3_600.0), random.nextLong(0, 1_000))
            val server = progress(random.nextDouble(0.0, 3_600.0), random.nextLong(0, 1_000))

            val first = ProgressConflictResolver.resolve(local, server)
            val settled = when (first) {
                is ProgressResolution.AdoptServer -> first.server
                is ProgressResolution.KeepLocal -> first.local
                ProgressResolution.InSync -> local
            }

            assertThat(ProgressConflictResolver.resolve(settled, server))
                .isNotInstanceOf(ProgressResolution.AdoptServer::class.java)
        }
    }
}
