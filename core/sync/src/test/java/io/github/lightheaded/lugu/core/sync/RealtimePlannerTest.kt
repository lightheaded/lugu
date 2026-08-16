package io.github.lightheaded.lugu.core.sync

import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.model.ProgressKey
import org.junit.Test

/**
 * What each hint is allowed to cause.
 *
 * The rule that matters most is the one about the item in the player: the server echoes
 * a progress event back to the device that caused it, so acting on one during playback
 * would have the app fighting its own writes — and a remote position may only reach a
 * playing item through the session-start path that resolves the conflict and offers an
 * undo. This is the guard, so it is tested rather than trusted to review.
 */
class RealtimePlannerTest {

    @Test
    fun `a changed item is re-fetched by id rather than parsed from the event`() {
        val work = RealtimePlanner.plan(RealtimeEvent.ItemsChanged(listOf("li_a")), playing = null)
        assertThat(work).isEqualTo(RealtimeWork.FetchItems(listOf("li_a")))
    }

    @Test
    fun `a removal is applied straight away`() {
        val work = RealtimePlanner.plan(RealtimeEvent.ItemsRemoved(listOf("li_gone")), playing = null)
        assertThat(work).isEqualTo(RealtimeWork.RemoveItems(listOf("li_gone")))
    }

    @Test
    fun `a library change re-reads the library list`() {
        assertThat(RealtimePlanner.plan(RealtimeEvent.LibrariesChanged, playing = null))
            .isEqualTo(RealtimeWork.FetchLibraries)
    }

    /** A scan touches the whole library; the sweep is the right tool for that, not this. */
    @Test
    fun `a batch too large to be worth re-fetching is left to the sweep`() {
        val many = (0..RealtimePlanner.MAX_ITEMS_PER_EVENT).map { "li_$it" }
        assertThat(RealtimePlanner.plan(RealtimeEvent.ItemsChanged(many), playing = null))
            .isEqualTo(RealtimeWork.Nothing)

        val justUnder = (1 until RealtimePlanner.MAX_ITEMS_PER_EVENT).map { "li_$it" }
        assertThat(RealtimePlanner.plan(RealtimeEvent.ItemsChanged(justUnder), playing = null))
            .isEqualTo(RealtimeWork.FetchItems(justUnder))
    }

    @Test
    fun `progress for something not playing is re-read from the server`() {
        val work = RealtimePlanner.plan(
            RealtimeEvent.ProgressChanged("li_a", null),
            playing = ProgressKey("li_b", null),
        )
        assertThat(work).isEqualTo(RealtimeWork.FetchProgress("li_a", null))
    }

    @Test
    fun `progress for the item in the player is never applied from here`() {
        val work = RealtimePlanner.plan(
            RealtimeEvent.ProgressChanged("li_a", null),
            playing = ProgressKey("li_a", null),
        )
        assertThat(work).isEqualTo(RealtimeWork.Nothing)
    }

    /** Two episodes of the same podcast are two positions; only the one playing is protected. */
    @Test
    fun `another episode of the podcast being played is still re-read`() {
        val work = RealtimePlanner.plan(
            RealtimeEvent.ProgressChanged("li_pod", "ep_2"),
            playing = ProgressKey("li_pod", "ep_1"),
        )
        assertThat(work).isEqualTo(RealtimeWork.FetchProgress("li_pod", "ep_2"))
    }

    @Test
    fun `the playing episode is protected even though the item matches`() {
        val work = RealtimePlanner.plan(
            RealtimeEvent.ProgressChanged("li_pod", "ep_1"),
            playing = ProgressKey("li_pod", "ep_1"),
        )
        assertThat(work).isEqualTo(RealtimeWork.Nothing)
    }

    @Test
    fun `a wholesale user change reconciles all progress`() {
        assertThat(RealtimePlanner.plan(RealtimeEvent.UserChanged, playing = null))
            .isEqualTo(RealtimeWork.ReconcileProgress)
    }
}
