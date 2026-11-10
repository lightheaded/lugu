package io.github.lightheaded.lugu.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Holding a seek button scans through the book. These pin its pace and its edges. */
class ScanPlanTest {

    @Test
    fun `the steps grow while the button is held`() {
        val steps = listOf(0L, 1_999L, 2_000L, 4_999L, 5_000L, 9_999L, 10_000L, 60_000L)
            .map(ScanPlan::stepSec)

        assertThat(steps).isEqualTo(listOf(5.0, 5.0, 15.0, 15.0, 30.0, 30.0, 60.0, 60.0))
        assertThat(steps).isInOrder()
    }

    @Test
    fun `a scan back moves the whole step from the last target`() {
        assertThat(ScanPlan.nextTarget(100.0, forward = false, stepSec = 15.0, durationSec = 3600.0))
            .isEqualTo(85.0)
    }

    @Test
    fun `a scan back stops at the start`() {
        assertThat(ScanPlan.nextTarget(3.0, forward = false, stepSec = 5.0, durationSec = 3600.0))
            .isEqualTo(0.0)
        assertThat(ScanPlan.nextTarget(0.0, forward = false, stepSec = 5.0, durationSec = 3600.0))
            .isNull()
    }

    @Test
    fun `a scan forward stops short of the end, so the book is never finished by it`() {
        val limit = 3600.0 - ScanPlan.END_MARGIN_SEC

        assertThat(ScanPlan.nextTarget(limit - 10.0, forward = true, stepSec = 60.0, durationSec = 3600.0))
            .isEqualTo(limit)
        assertThat(ScanPlan.nextTarget(limit, forward = true, stepSec = 60.0, durationSec = 3600.0))
            .isNull()
    }

    @Test
    fun `a forward scan in an item shorter than the margin does not move`() {
        assertThat(ScanPlan.nextTarget(0.0, forward = true, stepSec = 5.0, durationSec = 20.0))
            .isNull()
    }
}
