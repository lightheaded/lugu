package io.github.lightheaded.lugu.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.lightheaded.lugu.core.model.Chapters

/**
 * Makes the transport buttons mean what a listener expects on a book.
 *
 * Media3's stock behaviour is built for music. On a single-file audiobook —
 * one MediaItem, forty hours long — `seekToPrevious()` seeks to **position zero**, and
 * on a multi-file book "next" jumps to the next *file*, which is not a chapter. Either
 * way the notification and lock screen offer a one-tap button that destroys the
 * listener's place in the book, and the position it lands on is then persisted and
 * synced like any other seek.
 *
 * So previous/next are remapped to chapter navigation, falling back to a plain skip
 * when the item has no chapters. Nothing here can seek to the start of a book unless
 * the listener is already inside the first chapter and asks for it.
 */
@OptIn(UnstableApi::class)
class ChapterAwarePlayer(
    player: Player,
    private val stateHolder: PlaybackStateHolder,
    private val skipBackSec: Double = 30.0,
    private val skipForwardSec: Double = 30.0,
) : ForwardingPlayer(player) {

    private fun chapters() = stateHolder.nowPlaying.value?.chapters.orEmpty()

    private fun tracks() = stateHolder.nowPlaying.value?.tracks.orEmpty()

    private fun absolutePositionSec(): Double =
        AbsoluteTiming.toAbsoluteSec(tracks(), currentMediaItemIndex, currentPosition.coerceAtLeast(0))

    private fun seekToAbsolute(targetSec: Double) {
        val tracks = tracks()
        val safe = targetSec.coerceAtLeast(0.0)
        if (tracks.size <= 1) {
            super.seekTo((safe * 1000).toLong())
        } else {
            val position = AbsoluteTiming.toTrack(tracks, safe)
            super.seekTo(position.trackIndex, position.positionMs)
        }
    }

    override fun seekToPrevious() = goToPreviousChapter()

    override fun seekToPreviousMediaItem() = goToPreviousChapter()

    override fun seekToNext() = goToNextChapter()

    override fun seekToNextMediaItem() = goToNextChapter()

    private fun goToPreviousChapter() {
        val position = absolutePositionSec()
        val target = Chapters.previousChapterStart(chapters(), position)
            ?: (position - skipBackSec)
        seekToAbsolute(target)
    }

    private fun goToNextChapter() {
        val position = absolutePositionSec()
        val target = Chapters.nextChapterStart(chapters(), position)
            ?: (position + skipForwardSec)
        seekToAbsolute(target)
    }

    /**
     * Advertise the previous/next commands even for a single-file book, so the
     * notification offers chapter navigation rather than hiding the buttons.
     */
    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands()
            .buildUpon()
            .addAll(
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
            )
            .build()

    override fun isCommandAvailable(command: Int): Boolean = when (command) {
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_BACK,
        Player.COMMAND_SEEK_FORWARD,
        -> true

        else -> super.isCommandAvailable(command)
    }
}
