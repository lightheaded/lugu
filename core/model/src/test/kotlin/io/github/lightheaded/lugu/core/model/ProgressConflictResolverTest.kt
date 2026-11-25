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

    /** A row this device has read once and never pushed to. */
    private fun read(serverStamp: Long) = ServerCopySeen(lastUpdateMs = serverStamp)

    /** A row this device pushed a position to, and never read back. */
    private fun pushed(timeSec: Double, serverStamp: Long = 0L, finished: Boolean = false) =
        ServerCopySeen(lastUpdateMs = serverStamp, pushedTimeSec = timeSec, pushedFinished = finished)

    @Test
    fun `adopts a server position this device did not put there`() {
        val result = ProgressConflictResolver.resolve(
            local = progress(100.0, lastUpdate = 1_000),
            server = progress(900.0, lastUpdate = 2_000),
            seen = read(serverStamp = 1_500),
        )

        assertThat(result).isInstanceOf(ProgressResolution.AdoptServer::class.java)
        val adopt = result as ProgressResolution.AdoptServer
        assertThat(adopt.server.currentTimeSec).isEqualTo(900.0)
        assertThat(adopt.jumpSeconds).isEqualTo(800.0)
        assertThat(adopt.replacedLocal?.currentTimeSec).isEqualTo(100.0)
    }

    @Test
    fun `keeps local when the server is only echoing this device's own push`() {
        val result = ProgressConflictResolver.resolve(
            local = progress(900.0, lastUpdate = 5_000),
            server = progress(100.0, lastUpdate = 2_000),
            seen = pushed(timeSec = 100.0),
        )

        assertThat(result).isInstanceOf(ProgressResolution.KeepLocal::class.java)
    }

    /**
     * The failure this rule was rewritten for.
     *
     * `ProcessDeathResumptionTest` put a book at 49.9s, killed the process and found it
     * back at 19.8s: "the book resumed 30056ms behind". The server held 19.8s because
     * that was the last position the outbox managed to flush, and its `lastUpdate` read
     * later than the local row's — not because anything else had listened, but because
     * the two numbers came off different clocks and the server's ran ahead.
     *
     * The position is the evidence that settles it. 19.8s is the number this device
     * handed the server, so nothing else wrote it, and the 49.9s here is newer.
     */
    @Test
    fun `a stale server copy does not win on a device whose clock runs behind`() {
        val result = ProgressConflictResolver.resolve(
            local = progress(49.885, lastUpdate = 1_000),
            // Hours ahead, which is what a skewed server clock looks like.
            server = progress(19.829, lastUpdate = 9_999_999),
            seen = pushed(timeSec = 19.829),
        )

        assertThat(result).isInstanceOf(ProgressResolution.KeepLocal::class.java)
        assertThat((result as ProgressResolution.KeepLocal).local.currentTimeSec).isEqualTo(49.885)
    }

    /** The same skew, and this time the server really does hold another device's work. */
    @Test
    fun `another device still wins on a device whose clock runs behind`() {
        val result = ProgressConflictResolver.resolve(
            local = progress(49.885, lastUpdate = 1_000),
            server = progress(1_800.0, lastUpdate = 9_999_999),
            seen = pushed(timeSec = 19.829),
        )

        assertThat(result).isInstanceOf(ProgressResolution.AdoptServer::class.java)
    }

    /** Nothing has touched the row since it was read, so there is nothing to adopt. */
    @Test
    fun `an unchanged server stamp is not a foreign write`() {
        val result = ProgressConflictResolver.resolve(
            local = progress(900.0, lastUpdate = 1),
            server = progress(100.0, lastUpdate = 4_242),
            seen = read(serverStamp = 4_242),
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
            seen = read(serverStamp = 1_500),
        )

        assertThat(result).isInstanceOf(ProgressResolution.AdoptServer::class.java)
    }

    /**
     * A finished flag this device did not send is a foreign write even at the position
     * it did send, because the position alone cannot tell the two apart.
     */
    @Test
    fun `a finished flag this device never pushed is a foreign write`() {
        val result = ProgressConflictResolver.resolve(
            local = progress(3_600.0, lastUpdate = 1_000, finished = false),
            server = progress(3_600.0, lastUpdate = 2_000, finished = true),
            seen = pushed(timeSec = 3_600.0, finished = false),
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
     * A row this device knows nothing about the server's copy of adopts it.
     *
     * Stated as a test because it is the one case the rule gives up ground on, and it is
     * the price of not owning a clock both sides agree on. It needs the server to have
     * had no progress for the item at sign-in, this device to have listened with no
     * connection, and another device to have written the item in between. The undo the
     * UI offers for an adopted jump is what covers it.
     */
    @Test
    fun `with nothing known about the server the server wins`() {
        val result = ProgressConflictResolver.resolve(
            local = progress(900.0, lastUpdate = 5_000),
            server = progress(100.0, lastUpdate = 1),
            seen = ServerCopySeen(),
        )

        assertThat(result).isInstanceOf(ProgressResolution.AdoptServer::class.java)
    }

    /**
     * The invariant lugu exists to protect: an automatic push may never overwrite a
     * server copy that came from somewhere else. Checked over random histories rather
     * than hand-picked cases.
     *
     * The property is now stated in one clock. Its first version asserted that an
     * allowed push had `local.lastUpdateMs >= server.lastUpdateMs`, which reads as a
     * safety rule and is really a comparison of two unrelated clocks — the exact
     * mistake that let a stale server position win.
     */
    @Test
    fun `automatic pushes never overwrite a foreign server copy`() {
        val random = Random(seed = 20260902)
        repeat(2_000) {
            val server = progress(
                time = random.nextDouble(0.0, 3_600.0),
                lastUpdate = random.nextLong(0, 1_000_000),
                finished = random.nextBoolean(),
            )
            val seen = ServerCopySeen(
                lastUpdateMs = random.nextLong(0, 1_000_000),
                pushedTimeSec = random.nextDouble(0.0, 3_600.0).takeIf { random.nextBoolean() },
                pushedFinished = random.nextBoolean(),
            )

            val allowed = ProgressConflictResolver.mayPushAutomatically(server, seen)

            assertThat(allowed)
                .isEqualTo(!ProgressConflictResolver.serverCopyCameFromElsewhere(server, seen))
        }
    }

    /**
     * The guard and the resolution cannot disagree.
     *
     * A resolution of "keep local" followed by a guard that refuses the push is how a
     * position gets stranded on one phone forever, which is what happened while the two
     * asked their question of different clocks.
     */
    @Test
    fun `keeping local always permits the push that follows it`() {
        val random = Random(seed = 11)
        repeat(2_000) {
            val local = progress(random.nextDouble(0.0, 3_600.0), random.nextLong(0, 1_000))
            val server = progress(random.nextDouble(0.0, 3_600.0), random.nextLong(0, 1_000))
            val seen = ServerCopySeen(
                lastUpdateMs = random.nextLong(0, 1_000),
                pushedTimeSec = random.nextDouble(0.0, 3_600.0).takeIf { random.nextBoolean() },
            )

            if (ProgressConflictResolver.resolve(local, server, seen) is ProgressResolution.KeepLocal) {
                assertThat(ProgressConflictResolver.mayPushAutomatically(server, seen)).isTrue()
            }
        }
    }

    // -- The sweep's rule for a dirty row: newer listening wins --------------------------

    private val margin = ProgressConflictResolver.NEWER_LISTENING_MARGIN_MS

    /** The server stamp of the copy this device last read, before anybody listened. */
    private val readStamp = 1_699_000_000_000L

    /**
     * The maintainer's case. The phone listened for days while the sign-in had lapsed, and
     * another device wrote an older position before that. The phone's position goes out.
     */
    @Test
    fun `a sweep keeps a dirty row that was written days after another device's copy`() {
        val verdict = ProgressConflictResolver.sweepVerdict(
            local = progress(600.0, lastUpdate = readStamp + 3 * DAY_MS),
            server = progress(1_800.0, lastUpdate = readStamp + DAY_MS),
            seen = read(serverStamp = readStamp),
        )

        assertThat(verdict).isEqualTo(SweepVerdict.KEEP_LOCAL_NEWER)
    }

    /** Another device listened after the phone did, so its copy wins, as it always did. */
    @Test
    fun `a sweep takes another device's copy that was written after the dirty row`() {
        val verdict = ProgressConflictResolver.sweepVerdict(
            local = progress(600.0, lastUpdate = readStamp + DAY_MS),
            server = progress(1_800.0, lastUpdate = readStamp + 3 * DAY_MS),
            seen = read(serverStamp = readStamp),
        )

        assertThat(verdict).isEqualTo(SweepVerdict.TAKE_SERVER)
    }

    /** A finished mark is listening too: made offline and newer, it survives. */
    @Test
    fun `a sweep keeps a newer finished mark made offline`() {
        val verdict = ProgressConflictResolver.sweepVerdict(
            local = progress(3_600.0, lastUpdate = readStamp + 2 * DAY_MS, finished = true),
            server = progress(1_800.0, lastUpdate = readStamp + DAY_MS),
            seen = read(serverStamp = readStamp),
        )

        assertThat(verdict).isEqualTo(SweepVerdict.KEEP_LOCAL_NEWER)
    }

    /**
     * The margin is the line, on both sides. Newer by exactly the margin is not newer
     * enough; one millisecond more is.
     */
    @Test
    fun `the phone must be newer by more than the margin to win a sweep`() {
        val server = progress(1_800.0, lastUpdate = readStamp + DAY_MS)
        fun verdictAt(localMs: Long) = ProgressConflictResolver.sweepVerdict(
            local = progress(600.0, lastUpdate = localMs),
            server = server,
            seen = read(serverStamp = readStamp),
        )

        assertThat(verdictAt(server.lastUpdateMs + margin)).isEqualTo(SweepVerdict.TAKE_SERVER)
        assertThat(verdictAt(server.lastUpdateMs + margin + 1)).isEqualTo(SweepVerdict.KEEP_LOCAL_NEWER)
    }

    /**
     * Listening on two devices at about the same time is not clear, so it keeps the rule
     * from before: the server's copy wins, and the phone's position goes into the history.
     */
    @Test
    fun `a dirty row only minutes newer than another device's copy does not win a sweep`() {
        val verdict = ProgressConflictResolver.sweepVerdict(
            local = progress(600.0, lastUpdate = readStamp + DAY_MS + 5 * MINUTE_MS),
            server = progress(1_800.0, lastUpdate = readStamp + DAY_MS),
            seen = read(serverStamp = readStamp),
        )

        assertThat(verdict).isEqualTo(SweepVerdict.TAKE_SERVER)
    }

    /**
     * What the margin is for. The other device really listened later, and the phone's
     * clock runs ahead of the server's by up to the margin. Over random histories, the
     * phone's older position never wins.
     */
    @Test
    fun `a phone clock ahead by up to the margin never makes an older position win`() {
        val random = Random(seed = 20261125)
        repeat(2_000) {
            val phoneListenedAt = readStamp + random.nextLong(0, 7 * DAY_MS)
            val otherListenedAt = phoneListenedAt + random.nextLong(1, 3 * DAY_MS)
            val phoneClockAhead = random.nextLong(0, margin + 1)

            val verdict = ProgressConflictResolver.sweepVerdict(
                local = progress(600.0, lastUpdate = phoneListenedAt + phoneClockAhead),
                server = progress(1_800.0, lastUpdate = otherListenedAt),
                seen = read(serverStamp = readStamp),
            )

            assertThat(verdict).isEqualTo(SweepVerdict.TAKE_SERVER)
        }
    }

    /** No time on one side is no evidence, so the server's copy wins as before. */
    @Test
    fun `a sweep with a missing time on either side takes the server's copy`() {
        val withoutServerTime = ProgressConflictResolver.sweepVerdict(
            local = progress(600.0, lastUpdate = readStamp + DAY_MS),
            server = progress(1_800.0, lastUpdate = 0L),
            seen = read(serverStamp = readStamp),
        )
        val withoutLocalTime = ProgressConflictResolver.sweepVerdict(
            local = progress(600.0, lastUpdate = 0L),
            server = progress(1_800.0, lastUpdate = readStamp - DAY_MS),
            seen = ServerCopySeen(),
        )

        assertThat(withoutServerTime).isEqualTo(SweepVerdict.TAKE_SERVER)
        assertThat(withoutLocalTime).isEqualTo(SweepVerdict.TAKE_SERVER)
    }

    /**
     * A copy this device accounted for never replaces the row, whatever the times say.
     * Its stamp can read days ahead on a server whose clock runs fast.
     */
    @Test
    fun `a sweep keeps a dirty row over a copy this device accounted for`() {
        val sameStamp = ProgressConflictResolver.sweepVerdict(
            local = progress(600.0, lastUpdate = readStamp),
            server = progress(300.0, lastUpdate = readStamp + 3 * DAY_MS),
            seen = read(serverStamp = readStamp + 3 * DAY_MS),
        )
        val ownPush = ProgressConflictResolver.sweepVerdict(
            local = progress(600.0, lastUpdate = readStamp),
            server = progress(300.0, lastUpdate = readStamp + 3 * DAY_MS),
            seen = pushed(timeSec = 300.0, serverStamp = readStamp),
        )

        assertThat(sameStamp).isEqualTo(SweepVerdict.KEEP_LOCAL)
        assertThat(ownPush).isEqualTo(SweepVerdict.KEEP_LOCAL)
    }

    /**
     * The sweep and the check at a session start agree on a copy the sweep kept the row
     * over. The sweep records that copy's stamp; with it, [ProgressConflictResolver.resolve]
     * keeps the row too, so the push is not dropped before it goes out.
     */
    @Test
    fun `a copy the sweep kept the row over is not adopted at the next session start`() {
        val local = progress(600.0, lastUpdate = readStamp + 3 * DAY_MS)
        val server = progress(1_800.0, lastUpdate = readStamp + DAY_MS)
        val before = read(serverStamp = readStamp)
        assertThat(ProgressConflictResolver.sweepVerdict(local, server, before))
            .isEqualTo(SweepVerdict.KEEP_LOCAL_NEWER)

        val noted = before.copy(lastUpdateMs = server.lastUpdateMs)

        assertThat(ProgressConflictResolver.resolve(local, server, noted))
            .isInstanceOf(ProgressResolution.KeepLocal::class.java)
    }

    /** Resolution must be stable: resolving twice cannot flip the decision. */
    @Test
    fun `resolution is idempotent`() {
        val random = Random(seed = 7)
        repeat(1_000) {
            val local = progress(random.nextDouble(0.0, 3_600.0), random.nextLong(0, 1_000))
            val server = progress(random.nextDouble(0.0, 3_600.0), random.nextLong(0, 1_000))
            val seen = ServerCopySeen(
                lastUpdateMs = random.nextLong(0, 1_000),
                pushedTimeSec = random.nextDouble(0.0, 3_600.0).takeIf { random.nextBoolean() },
            )

            val first = ProgressConflictResolver.resolve(local, server, seen)
            val settled = when (first) {
                is ProgressResolution.AdoptServer -> first.server
                is ProgressResolution.KeepLocal -> first.local
                ProgressResolution.InSync -> local
            }
            // What the repository stores after adopting: the server's own stamp.
            val settledSeen = when (first) {
                is ProgressResolution.AdoptServer -> seen.copy(lastUpdateMs = server.lastUpdateMs)
                else -> seen
            }

            assertThat(ProgressConflictResolver.resolve(settled, server, settledSeen))
                .isNotInstanceOf(ProgressResolution.AdoptServer::class.java)
        }
    }

    private companion object {
        const val MINUTE_MS = 60 * 1_000L
        const val DAY_MS = 24 * 60 * MINUTE_MS
    }
}
