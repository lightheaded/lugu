package io.github.lightheaded.lugu.core.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The cap used to be checked once, against an estimate, before the first byte. These are the
 * cases that got past it.
 */
class StorageCapTest {

    private val gigabyte = 1024L * 1024 * 1024

    private fun action(usedGb: Long, capGb: Long, inFlight: Boolean = true) =
        StorageCap.actionFor(usedGb * gigabyte, capGb * gigabyte, inFlight)

    @Test
    fun `a download that stays under the cap runs to the end`() {
        assertThat(action(usedGb = 3, capGb = 8)).isEqualTo(CapAction.CARRY_ON)
    }

    /**
     * The overshoot case: the estimate said the book would fit, the book was larger than the
     * server claimed, and until now nothing looked again.
     */
    @Test
    fun `a book larger than its reported size is stopped in flight`() {
        assertThat(action(usedGb = 9, capGb = 8)).isEqualTo(CapAction.STOP_IN_FLIGHT)
    }

    /**
     * Exactly at the cap counts as reaching it. A margin would make the in-flight rule
     * stricter than the pre-flight one, so a download accepted a second ago would be stopped
     * for a reason the arithmetic on the screen contradicts.
     */
    @Test
    fun `landing exactly on the cap is reaching it`() {
        assertThat(action(usedGb = 8, capGb = 8)).isEqualTo(CapAction.STOP_IN_FLIGHT)
    }

    /**
     * A phone whose downloads already fill the cap is not doing anything wrong; it is simply
     * full. Stopping nothing, every second, would fill the record with an event that never
     * ends and would put a failure message on a row that had already finished.
     */
    @Test
    fun `a full phone with nothing running is left alone`() {
        assertThat(action(usedGb = 9, capGb = 8, inFlight = false)).isEqualTo(CapAction.CARRY_ON)
    }

    /** Zero is how the settings screen says "no cap"; it must not read as a cap of nothing. */
    @Test
    fun `no cap means no cap`() {
        assertThat(StorageCap.actionFor(500L * gigabyte, 0, anythingInFlight = true))
            .isEqualTo(CapAction.CARRY_ON)
        assertThat(StorageCap.actionFor(500L * gigabyte, -1, anythingInFlight = true))
            .isEqualTo(CapAction.CARRY_ON)
    }

    /**
     * Held to the same standard as `DownloadRefusedException`: state the arithmetic, say what
     * happened to the bytes, name the fix. A stop with no numbers in it reads as the app
     * having broken rather than as a cap having been reached.
     */
    @Test
    fun `the message states its arithmetic and names the fix`() {
        val text = StorageCap.stoppedMessage(bytesOnDisk = 8 * gigabyte, capBytes = 8 * gigabyte)

        assertThat(text).contains("8.0 GB cap")
        assertThat(text).contains("8.0 GB used")
        assertThat(text).contains("already been fetched is kept")
        assertThat(text).contains("Raise the cap in Settings")
    }

    /**
     * The fear a stopped download raises is that the gigabyte already fetched has been thrown
     * away, and the answer to "start it again" is different depending on whether it resumes
     * or restarts. So the message says so, and never claims something failed.
     */
    @Test
    fun `the message never says the download failed`() {
        val text = StorageCap.stoppedMessage(bytesOnDisk = 8 * gigabyte, capBytes = 8 * gigabyte)

        assertThat(text.lowercase()).doesNotContain("fail")
        assertThat(text.lowercase()).doesNotContain("error")
        assertThat(text.lowercase()).doesNotContain("deleted")
    }

    /** Nothing pending-delete: the reading is exactly what is on disk. */
    @Test
    fun `chargeableBytes with nothing pending is the disk figure`() {
        assertThat(StorageCap.chargeableBytes(bytesOnDisk = 3 * gigabyte, pendingDeleteBytes = 0))
            .isEqualTo(3 * gigabyte)
    }

    /**
     * A completed download marked pending-delete still occupies its bytes, but the tap
     * that marked it already asked for them back — so a fresh download waiting on that
     * same space must see it as free.
     */
    @Test
    fun `a pending delete's bytes are subtracted`() {
        assertThat(StorageCap.chargeableBytes(bytesOnDisk = 3 * gigabyte, pendingDeleteBytes = gigabyte))
            .isEqualTo(2 * gigabyte)
    }

    /**
     * `bytesOnDisk` and `pendingDeleteBytes` come from two different reads a moment
     * apart, so a delete landing between them must not turn the answer negative.
     */
    @Test
    fun `chargeableBytes never goes negative`() {
        assertThat(StorageCap.chargeableBytes(bytesOnDisk = gigabyte, pendingDeleteBytes = 2 * gigabyte))
            .isEqualTo(0)
    }
}
