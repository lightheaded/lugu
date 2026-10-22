package io.github.lightheaded.lugu.playback

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * "I press play on something from Home, and it opens the old item", through the second way
 * in: a headset or the system's media controls ask for a resumption while the app's own
 * request is still on its way.
 *
 * The app's request runs through the real [PlayStarter] and the resumption through the real
 * [ResumptionGate], on one [PlayRequests]. The network is two deferreds the test completes
 * by hand. The player is a list of what was loaded into it, in order. The ids are invented.
 */
class ResumptionGateTest {

    private val requests = PlayRequests()
    private val loaded = mutableListOf<String>()

    private val startAnswer = CompletableDeferred<Result<PreparedStart>>()
    private val starter = PlayStarter(
        requests = requests,
        prepare = { _, _ -> startAnswer.await() },
        load = { loaded += it.nowPlaying.libraryItemId },
    )

    private val lastPlayed = CompletableDeferred<Resumption?>()
    private val gate = ResumptionGate(
        requests = requests,
        playerHoldsItem = { loaded.isNotEmpty() },
        resolveLastPlayed = { lastPlayed.await() },
    )

    /** What the service does with an outcome: only [ResumptionOutcome.Resume] loads. */
    private suspend fun resume(): ResumptionOutcome {
        val outcome = gate.decide()
        if (outcome is ResumptionOutcome.Resume) loaded += outcome.resumption.nowPlaying.libraryItemId
        return outcome
    }

    /**
     * The race. The resumption begins on an empty player, the listener presses play on a new
     * item, and the new item loads before the last one has resolved. Before the gate, Media3
     * then loaded the last item over the new one and played it.
     */
    @Test
    fun `a request that loads while the last item resolves is not replaced`() = runTest {
        val resumption = async { resume() }
        runCurrent()
        async { starter.start("item-new", null) }
        runCurrent()

        startAnswer.complete(Result.success(prepared("item-new")))
        runCurrent()
        lastPlayed.complete(resumed("item-last"))
        runCurrent()

        assertThat(resumption.await()).isEqualTo(ResumptionOutcome.Superseded)
        assertThat(loaded).containsExactly("item-new")
    }

    /**
     * The same race with the request slower than the resumption. The player is still empty
     * when the last item resolves, so a check of the player alone would let it in.
     */
    @Test
    fun `a request still starting when the last item resolves wins, and is waited for`() = runTest {
        val resumption = async { resume() }
        runCurrent()
        async { starter.start("item-new", null) }
        runCurrent()

        lastPlayed.complete(resumed("item-last"))
        runCurrent()
        assertThat(resumption.isCompleted).isFalse()
        assertThat(loaded).isEmpty()

        startAnswer.complete(Result.success(prepared("item-new")))
        runCurrent()

        assertThat(resumption.await()).isEqualTo(ResumptionOutcome.Superseded)
        assertThat(loaded).containsExactly("item-new")
    }

    /** The request was there first. The resumption does not even resolve. */
    @Test
    fun `a resumption that arrives while a request is starting leaves it alone`() = runTest {
        async { starter.start("item-new", null) }
        runCurrent()
        val resumption = async { resume() }
        runCurrent()

        startAnswer.complete(Result.success(prepared("item-new")))
        runCurrent()

        assertThat(resumption.await()).isEqualTo(ResumptionOutcome.Superseded)
        assertThat(loaded).containsExactly("item-new")
    }

    /** A pause during the resolution means stop, the same as for a request. */
    @Test
    fun `a pause while the last item resolves stops the resumption`() = runTest {
        val resumption = async { resume() }
        runCurrent()

        requests.abandon()
        lastPlayed.complete(resumed("item-last"))
        runCurrent()

        assertThat(resumption.await()).isEqualTo(ResumptionOutcome.Superseded)
        assertThat(loaded).isEmpty()
    }

    /** A request that never answers does not hold the resumption, or the player, forever. */
    @Test
    fun `the wait for a request has a limit, and the player is still left alone`() = runTest {
        async { starter.start("item-slow", null) }
        runCurrent()
        val resumption = async { resume() }
        runCurrent()

        advanceTimeBy(ResumptionGate.START_WAIT_MS + 1)
        runCurrent()

        assertThat(resumption.await()).isEqualTo(ResumptionOutcome.Superseded)
        assertThat(loaded).isEmpty()
        startAnswer.complete(Result.success(prepared("item-slow")))
    }

    @Test
    fun `something loaded from elsewhere while resolving stays`() = runTest {
        val resumption = async { resume() }
        runCurrent()

        loaded += "item-from-a-car"
        lastPlayed.complete(resumed("item-last"))
        runCurrent()

        assertThat(resumption.await()).isEqualTo(ResumptionOutcome.AlreadyLoaded)
        assertThat(loaded).containsExactly("item-from-a-car")
    }

    /** The path the feature exists for: nothing else is going on, so the last item resumes. */
    @Test
    fun `with nothing else going on the last item resumes`() = runTest {
        lastPlayed.complete(resumed("item-last"))

        assertThat(resume()).isInstanceOf(ResumptionOutcome.Resume::class.java)
        assertThat(loaded).containsExactly("item-last")
    }

    @Test
    fun `with nothing to resume the outcome says so`() = runTest {
        lastPlayed.complete(null)

        assertThat(resume()).isEqualTo(ResumptionOutcome.NothingToResume)
        assertThat(loaded).isEmpty()
    }

    /**
     * A media button that played nothing, in CI, left no evidence of whether the resumption
     * ran. Each outcome now has its own line, and the lines must stay told apart.
     */
    @Test
    fun `each outcome has its own log line`() {
        val lines = listOf(
            ResumptionOutcome.Resume(resumed("item-last")),
            ResumptionOutcome.NothingToResume,
            ResumptionOutcome.Superseded,
            ResumptionOutcome.AlreadyLoaded,
        ).map { it.describe() } + describeResumptionFailure(IllegalStateException("x"))

        assertThat(lines.toSet()).hasSize(lines.size)
    }

    @Test
    fun `the log line names what reached the player and no item`() {
        val line = Resumption(
            mediaItems = emptyList(),
            startTrackIndex = 2,
            startPositionMs = 45_000L,
            nowPlaying = nowPlaying("item-secret"),
        ).let { ResumptionOutcome.Resume(it).describe() }

        assertThat(line).contains("track 2 at 45000 ms")
        assertThat(line).doesNotContain("item-secret")
        assertThat(line).doesNotContain("Invented title")
    }

    /** An exception message can carry a server address, and the log line must not. */
    @Test
    fun `a failure is logged by its class, never its message`() {
        val line = describeResumptionFailure(java.io.IOException("Unable to resolve host example.invalid"))

        assertThat(line).contains("IOException")
        assertThat(line).doesNotContain("example.invalid")
    }

    private fun nowPlaying(itemId: String) = NowPlaying(
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
    )

    private fun resumed(itemId: String) = Resumption(
        mediaItems = emptyList(),
        startTrackIndex = 0,
        startPositionMs = 0L,
        nowPlaying = nowPlaying(itemId),
    )

    private fun prepared(itemId: String) = PreparedStart(
        nowPlaying = nowPlaying(itemId),
        jump = null,
        mediaItems = emptyList(),
        startTrackIndex = 0,
        startPositionMs = 0L,
        speed = 1.0f,
    )
}
