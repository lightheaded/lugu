package io.github.lightheaded.lugu.playback

import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.lightheaded.lugu.core.model.AudioTrack
import io.github.lightheaded.lugu.core.model.Chapter
import io.github.lightheaded.lugu.core.sync.PlayerSettings
import io.github.lightheaded.lugu.core.sync.TransportButton
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The class that stands between the notification and someone's place in a book.
 *
 * It exists because Media3's stock transport is built for music, and it has now been
 * the cause of two separate user-visible faults: a rewind that reset a forty-hour book
 * to zero, and a notification that jumped in ten-minute steps. Both were behaviours no
 * type checker could have objected to, which is what these tests are for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(UnstableApi::class)
class ChapterAwarePlayerTest {

    /**
     * A minimal stand-in that records where it was asked to seek. Media3's own fakes live
     * in a test artifact that would pull the whole ExoPlayer test stack into this module.
     */
    private class RecordingPlayer(
        private val durationSec: Double,
        private var positionMs: Long,
    ) : SimpleBasePlayer(android.os.Looper.getMainLooper()) {
        var seekedToMs: Long? = null
        var seekedToMediaItemIndex: Int? = null

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(
                listOf(
                    MediaItemData.Builder("only")
                        .setDurationUs((durationSec * 1_000_000).toLong())
                        .build(),
                ),
            )
            .setContentPositionMs(positionMs)
            .build()

        override fun handleSeek(
            mediaItemIndex: Int,
            positionMs: Long,
            seekCommand: Int,
        ): ListenableFuture<*> {
            seekedToMediaItemIndex = mediaItemIndex
            seekedToMs = positionMs
            this.positionMs = positionMs
            return Futures.immediateVoidFuture()
        }
    }

    private fun playerAt(
        positionSec: Double,
        chapters: List<Chapter>,
        settings: PlayerSettings,
    ): Pair<ChapterAwarePlayer, RecordingPlayer> {
        // Position is set at construction: SimpleBasePlayer reads its state once and
        // caches it, so changing it afterwards would not be visible to the wrapper.
        val inner = RecordingPlayer(durationSec = 40 * 3600.0, positionMs = (positionSec * 1000).toLong())

        val stateHolder = PlaybackStateHolder()
        stateHolder.set(
            NowPlaying(
                libraryItemId = "li_1",
                episodeId = null,
                title = "A Book",
                author = null,
                coverUrl = null,
                durationSec = 40 * 3600.0,
                tracks = listOf(
                    AudioTrack(
                        index = 1,
                        startOffsetSec = 0.0,
                        durationSec = 40 * 3600.0,
                        contentUrl = "https://books.example/file",
                        mimeType = "audio/mp4",
                    ),
                ),
                chapters = chapters,
                ledgerId = "ledger",
                isTranscoded = false,
            ),
        )
        return ChapterAwarePlayer(inner, stateHolder) { settings } to inner
    }

    /** Synthetic chapters are ten minutes apart, which is what made the notification jump. */
    private fun syntheticChapters(): List<Chapter> = (0 until 240).map {
        Chapter(it, it * 600.0, (it + 1) * 600.0, "Part ${it + 1}")
    }

    private val seekOnly = PlayerSettings(
        skipBackSec = 15,
        skipForwardSec = 30,
        notificationButtons = listOf(TransportButton.SKIP_BACK, TransportButton.SKIP_FORWARD),
    )

    private val withChapters = PlayerSettings(
        skipBackSec = 15,
        skipForwardSec = 30,
        notificationButtons = listOf(
            TransportButton.SKIP_BACK,
            TransportButton.SKIP_FORWARD,
            TransportButton.PREVIOUS_CHAPTER,
            TransportButton.NEXT_CHAPTER,
        ),
    )

    /**
     * The reported bug. At 65 minutes in, with synthetic ten-minute chapters, "previous"
     * used to restart the chapter at 60:00 — a five-minute jump — and "next" moved a
     * full ten minutes.
     */
    @Test
    fun `the notification skips by the configured seconds, not by a chapter`() {
        val (player, inner) = playerAt(3900.0, syntheticChapters(), seekOnly)

        player.seekToPrevious()
        assertThat(inner.seekedToMs).isEqualTo(3885_000L)

        val (forward, forwardInner) = playerAt(3900.0, syntheticChapters(), seekOnly)
        forward.seekToNext()
        assertThat(forwardInner.seekedToMs).isEqualTo(3930_000L)
    }

    @Test
    fun `chapter navigation still happens when it was asked for`() {
        val (player, inner) = playerAt(3900.0, syntheticChapters(), withChapters)

        player.seekToNext()

        // Start of the next ten-minute part, not a thirty-second nudge.
        assertThat(inner.seekedToMs).isEqualTo(4200_000L)
    }

    /**
     * The original data-loss bug: on a single-file book, Media3's `seekToPrevious()`
     * seeks to zero. Forty hours lost to one tap on a lock screen.
     */
    @Test
    fun `previous never seeks a book back to zero`() {
        val (player, inner) = playerAt(20 * 3600.0, chapters = emptyList(), settings = seekOnly)

        player.seekToPrevious()
        player.seekToPreviousMediaItem()

        assertThat(inner.seekedToMs).isNotEqualTo(0L)
        assertThat(inner.seekedToMs).isAtLeast((20 * 3600 - 60) * 1000L)
    }

    @Test
    fun `a book with no chapters still skips by the configured seconds`() {
        val (player, inner) = playerAt(1000.0, chapters = emptyList(), settings = withChapters)

        player.seekToPrevious()

        assertThat(inner.seekedToMs).isEqualTo(985_000L)
    }

    @Test
    fun `seeking back at the very start clamps to zero rather than going negative`() {
        val (player, inner) = playerAt(5.0, chapters = emptyList(), settings = seekOnly)

        player.seekToPrevious()

        assertThat(inner.seekedToMs).isEqualTo(0L)
    }

    @Test
    fun `seek back and forward honour the configured durations`() {
        val (player, inner) = playerAt(1000.0, syntheticChapters(), seekOnly)

        player.seekBack()
        assertThat(inner.seekedToMs).isEqualTo(985_000L)

        val (forward, forwardInner) = playerAt(1000.0, syntheticChapters(), seekOnly)
        forward.seekForward()
        assertThat(forwardInner.seekedToMs).isEqualTo(1030_000L)
    }

    @Test
    fun `increments are reported so the system shows the right numbers`() {
        val (player, _) = playerAt(0.0, emptyList(), seekOnly)

        assertThat(player.seekBackIncrement).isEqualTo(15_000L)
        assertThat(player.seekForwardIncrement).isEqualTo(30_000L)
    }

    /**
     * Withdrawing these was what left the notification either button-less or unchanged;
     * they must stay advertised so the buttons exist and the behaviour switch decides
     * what they do.
     */
    @Test
    fun `transport commands stay available whatever the settings say`() {
        listOf(seekOnly, withChapters).forEach { settings ->
            val (player, _) = playerAt(0.0, emptyList(), settings)

            assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)).isTrue()
            assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isTrue()
            assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_BACK)).isTrue()
            assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)).isTrue()
        }
    }
}
