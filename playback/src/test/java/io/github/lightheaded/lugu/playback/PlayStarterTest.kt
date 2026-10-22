package io.github.lightheaded.lugu.playback

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * "I press play on something from Home, and it opens the old item." Reported on an
 * unstable network.
 *
 * The network is the fake here: every request waits on a deferred that the test completes
 * by hand, in whatever order a bad connection would. The player is a list of what was
 * loaded into it. The ids are invented.
 */
class PlayStarterTest {

    private val requests = PlayRequests()
    private val answers = mutableMapOf<String, CompletableDeferred<Result<PreparedStart>>>()
    private val loaded = mutableListOf<String>()

    private val starter = PlayStarter(
        requests = requests,
        prepare = { itemId, _ -> answers.getOrPut(itemId) { CompletableDeferred() }.await() },
        load = { loaded += it.nowPlaying.libraryItemId },
    )

    private fun answer(itemId: String) =
        answers.getOrPut(itemId) { CompletableDeferred() }.complete(Result.success(prepared(itemId)))

    private fun fail(itemId: String) =
        answers.getOrPut(itemId) { CompletableDeferred() }.complete(Result.failure(IOException("timeout")))

    /**
     * The bug. The old item's request was still waiting on the server when the new one was
     * made, the new one answered first, and the old one's late answer was loaded on top.
     */
    @Test
    fun `a late answer for an earlier request does not replace the newer one`() = runTest {
        val old = async { starter.start("item-old", null) }
        runCurrent()
        val new = async { starter.start("item-new", null) }
        runCurrent()

        answer("item-new")
        runCurrent()
        answer("item-old")
        runCurrent()

        assertThat(loaded).containsExactly("item-new")
        assertThat(old.await()).isEqualTo(StartOutcome.Superseded)
        assertThat(new.await()).isEqualTo(StartOutcome.Loaded)
    }

    /** The same race the other way round: the old one answers first, then the new one. */
    @Test
    fun `an earlier request that answers first is not loaded at all`() = runTest {
        async { starter.start("item-old", null) }
        runCurrent()
        async { starter.start("item-new", null) }
        runCurrent()

        answer("item-old")
        runCurrent()
        assertThat(loaded).isEmpty()

        answer("item-new")
        runCurrent()
        assertThat(loaded).containsExactly("item-new")
    }

    /** The player screen opens on the press, long before a slow network answers. */
    @Test
    fun `the requested item is reported at once and until it is loaded`() = runTest {
        async { starter.start("item-new", "episode-1") }
        runCurrent()

        assertThat(requests.starting.value).isEqualTo(StartingItem("item-new", "episode-1"))

        answer("item-new")
        runCurrent()

        assertThat(requests.starting.value).isNull()
    }

    @Test
    fun `a failure is reported for the newest request and ends it`() = runTest {
        val new = async { starter.start("item-new", null) }
        runCurrent()

        fail("item-new")
        runCurrent()

        assertThat(new.await()).isInstanceOf(StartOutcome.Failed::class.java)
        assertThat(requests.starting.value).isNull()
        assertThat(loaded).isEmpty()
    }

    /** An error about something the listener has already moved away from is only noise. */
    @Test
    fun `a failure for an earlier request says nothing and leaves the newer one starting`() = runTest {
        val old = async { starter.start("item-old", null) }
        runCurrent()
        val new = async { starter.start("item-new", null) }
        runCurrent()

        fail("item-old")
        runCurrent()

        assertThat(old.await()).isEqualTo(StartOutcome.Superseded)
        assertThat(requests.starting.value).isEqualTo(StartingItem("item-new", null))

        answer("item-new")
        assertThat(new.await()).isEqualTo(StartOutcome.Loaded)
    }

    /** Pause while the start is pending means stop. The answer must not start the audio. */
    @Test
    fun `a pause while starting drops the start`() = runTest {
        val new = async { starter.start("item-new", null) }
        runCurrent()

        requests.abandon()
        answer("item-new")
        runCurrent()

        assertThat(new.await()).isEqualTo(StartOutcome.Superseded)
        assertThat(loaded).isEmpty()
        assertThat(requests.starting.value).isNull()
    }

    /**
     * A downloaded item resolves from disk with no network, so its answer is immediate.
     * The ordering adds no wait of its own to that path.
     */
    @Test
    fun `an answer that is already there loads with no wait`() = runTest {
        answer("item-downloaded")

        val outcome = starter.start("item-downloaded", null)

        assertThat(outcome).isEqualTo(StartOutcome.Loaded)
        assertThat(loaded).containsExactly("item-downloaded")
    }

    private fun prepared(itemId: String) = PreparedStart(
        nowPlaying = NowPlaying(
            libraryItemId = itemId,
            episodeId = null,
            title = "Invented title",
            author = null,
            coverUrl = null,
            durationSec = 60.0,
            tracks = emptyList(),
            chapters = emptyList(),
            ledgerId = "ledger-$itemId",
            isTranscoded = false,
        ),
        jump = null,
        mediaItems = emptyList(),
        startTrackIndex = 0,
        startPositionMs = 0L,
        speed = 1.0f,
    )
}
