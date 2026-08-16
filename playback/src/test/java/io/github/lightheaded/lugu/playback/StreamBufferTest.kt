package io.github.lightheaded.lugu.playback

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.sync.StreamSettings
import org.junit.Test

/**
 * The plan is two promises that can contradict each other — this many minutes, no more than
 * this much memory — and the tests are about which one wins where. Getting that wrong in the
 * generous direction is an out-of-memory kill on somebody's commute.
 */
class StreamBufferTest {

    /** A roomy but unremarkable phone: 256 MB of heap for the app. */
    private val ordinaryHeapBytes = 256L * 1024 * 1024

    @Test
    fun `the setting is what the player is told to buffer`() {
        val plan = StreamBuffer.planFor(bufferAheadMinutes = 5, maxHeapBytes = ordinaryHeapBytes)

        assertThat(plan.minBufferMs).isEqualTo(5 * 60_000)
        assertThat(plan.maxBufferMs).isEqualTo(5 * 60_000)
    }

    /**
     * The point of the whole feature: fifty seconds was Media3's default and a tunnel drains
     * it. Anything short of minutes is not an answer to the complaint.
     */
    @Test
    fun `the default buys minutes rather than seconds`() {
        val plan = StreamBuffer.planFor(
            bufferAheadMinutes = StreamSettings().bufferAheadMinutes,
            maxHeapBytes = ordinaryHeapBytes,
        )

        assertThat(plan.minBufferMs).isAtLeast(3 * 60_000)
    }

    /**
     * At the bitrate an audiobook is actually encoded at, even the largest setting fits
     * inside the ceiling with room to spare. If this stops being true the ceiling has become
     * the setting, which is not what a setting is for.
     */
    @Test
    fun `a spoken-word book is never clipped by the byte ceiling`() {
        val bytesPerSecondAt64Kbps = 8_000

        val plan = StreamBuffer.planFor(
            bufferAheadMinutes = StreamSettings.MAX_BUFFER_MINUTES,
            maxHeapBytes = ordinaryHeapBytes,
        )

        val bytesTheBookWouldNeed =
            StreamSettings.MAX_BUFFER_MINUTES * 60 * bytesPerSecondAt64Kbps
        assertThat(plan.targetBufferBytes).isAtLeast(bytesTheBookWouldNeed)
    }

    /**
     * The ceiling is the bitrate assumption made arithmetic: five minutes is allowed 32 kB
     * for every second of it, and a file encoded above that gets fewer minutes rather than
     * more memory.
     */
    @Test
    fun `the ceiling encodes 256 kbps`() {
        val plan = StreamBuffer.planFor(bufferAheadMinutes = 5, maxHeapBytes = ordinaryHeapBytes)

        assertThat(plan.targetBufferBytes).isEqualTo(5 * 60 * StreamBuffer.ASSUMED_BYTES_PER_SECOND)
    }

    @Test
    fun `a small heap wins over the setting`() {
        val smallHeapBytes = 48L * 1024 * 1024

        val plan = StreamBuffer.planFor(
            bufferAheadMinutes = StreamSettings.MAX_BUFFER_MINUTES,
            maxHeapBytes = smallHeapBytes,
        )

        assertThat(plan.targetBufferBytes)
            .isAtMost((smallHeapBytes / StreamBuffer.HEAP_SHARE_DIVISOR).toInt())
    }

    /** A generous heap is not a reason to spend it; the cover art cache lives there too. */
    @Test
    fun `a large heap does not lift the absolute ceiling`() {
        val plan = StreamBuffer.planFor(
            bufferAheadMinutes = StreamSettings.MAX_BUFFER_MINUTES,
            maxHeapBytes = 4L * 1024 * 1024 * 1024,
        )

        assertThat(plan.targetBufferBytes).isAtMost(StreamBuffer.MAX_TARGET_BYTES)
    }

    /**
     * A buffer of a few hundred kilobytes is not a small buffer but a broken one, and a heap
     * this size only reaches the plan through arithmetic nobody intended.
     */
    @Test
    fun `an absurd heap still leaves a workable buffer`() {
        val plan = StreamBuffer.planFor(bufferAheadMinutes = 5, maxHeapBytes = 1024)

        assertThat(plan.targetBufferBytes).isEqualTo(StreamBuffer.MIN_TARGET_BYTES)
    }

    /**
     * The setting arrives from a preferences file, and a restore from a backup written by a
     * later version can put anything in it. A zero would make `setBufferDurationsMs` throw
     * and take the whole service down with it.
     */
    @Test
    fun `a nonsense setting is clamped rather than trusted`() {
        assertThat(StreamBuffer.planFor(0, ordinaryHeapBytes).minBufferMs).isEqualTo(60_000)
        assertThat(StreamBuffer.planFor(-4, ordinaryHeapBytes).minBufferMs).isEqualTo(60_000)
        assertThat(StreamBuffer.planFor(9_999, ordinaryHeapBytes).minBufferMs)
            .isEqualTo(StreamSettings.MAX_BUFFER_MINUTES * 60_000)
    }

    /**
     * Media3 refuses a plan whose start threshold is longer than its minimum, so the
     * relationship is asserted rather than left to the reader of two constants.
     */
    @Test
    fun `starting playback never waits for the whole buffer`() {
        val plan = StreamBuffer.planFor(1, ordinaryHeapBytes)

        assertThat(StreamBuffer.BUFFER_FOR_PLAYBACK_MS).isAtMost(plan.minBufferMs)
        assertThat(StreamBuffer.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS).isAtMost(plan.minBufferMs)
        assertThat(plan.maxBufferMs).isAtLeast(plan.minBufferMs)
    }
}
