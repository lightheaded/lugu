package io.github.lightheaded.lugu.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.lightheaded.lugu.core.model.Chapters
import io.github.lightheaded.lugu.core.sync.PlayerSettings

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
 * So previous/next are remapped. What they are remapped *to* follows the notification
 * setting: the configured skip by default, chapter navigation only when the listener
 * has asked for chapter buttons. Nothing here can seek to the start of a book unless
 * the listener is already inside the first chapter and asks for it.
 *
 * These entry points are the *system* transport only — the notification, lock screen,
 * headset and Android Auto. The in-app chapter buttons go through
 * [PlaybackConnection.previousChapter]/[PlaybackConnection.nextChapter] and are
 * unaffected by this, so an explicit chapter button always navigates chapters.
 */
@OptIn(UnstableApi::class)
class ChapterAwarePlayer(
    player: Player,
    private val stateHolder: PlaybackStateHolder,
    /** Read live, so changing a setting takes effect without restarting playback. */
    private val settings: () -> PlayerSettings,
) : ForwardingPlayer(player) {

    private val skipBackSec: Double get() = settings().skipBackSec.toDouble()
    private val skipForwardSec: Double get() = settings().skipForwardSec.toDouble()

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

    override fun seekToPrevious() = systemPrevious()

    override fun seekToPreviousMediaItem() = systemPrevious()

    override fun seekToNext() = systemNext()

    override fun seekToNextMediaItem() = systemNext()

    /**
     * What the notification's left button does.
     *
     * Skipping back by the configured seconds unless chapter buttons were asked for.
     * Chapter navigation was the unconditional behaviour, and it made the notification
     * jump in ten-minute steps on any book without real chapters — because a chapterless
     * book gets synthetic ten-minute chapters, and on a multi-file book the step is a
     * whole file. Neither is what someone reaching for the notification wants: that
     * button exists to catch a sentence they missed.
     */
    private fun systemPrevious() {
        val position = absolutePositionSec()
        val target = if (usesChapterTransport()) {
            Chapters.previousChapterStart(chapters(), position) ?: (position - skipBackSec)
        } else {
            position - skipBackSec
        }
        seekToAbsolute(target)
    }

    private fun systemNext() {
        val position = absolutePositionSec()
        val target = if (usesChapterTransport()) {
            Chapters.nextChapterStart(chapters(), position) ?: (position + skipForwardSec)
        } else {
            position + skipForwardSec
        }
        seekToAbsolute(target)
    }

    private fun usesChapterTransport(): Boolean = settings().showsChapterButtonsInNotification

    /**
     * The transport commands are advertised unconditionally, and what they *do* is what
     * the settings control.
     *
     * This used to withdraw the previous/next commands when chapter buttons were not
     * wanted, on the assumption the system would then offer seek buttons instead. It
     * does not: Media3's default notification provider builds its layout from
     * previous / play-pause / next and has no seek-back button to fall back to, so
     * withdrawing those commands removes the side buttons rather than changing them.
     * Worse, available commands are read when a controller connects and nothing here
     * fires a change when a setting moves, so the withdrawal was not reliably seen at
     * all — which is how the notification ended up still doing chapter-sized jumps.
     *
     * Keeping the commands stable and switching their behaviour is deterministic, and
     * needs no cooperation from the notification provider. Giving those two slots proper
     * seek *icons*, and controlling their order, needs a Media3 custom layout — see
     * docs/BACKLOG.md.
     */
    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .addAll(
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT,
            )
            .build()

    override fun isCommandAvailable(command: Int): Boolean = when (command) {
        Player.COMMAND_SEEK_BACK,
        Player.COMMAND_SEEK_FORWARD,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_NEXT,
        -> true

        else -> super.isCommandAvailable(command)
    }

    /** Seek back/forward honour the configured durations rather than Media3 defaults. */
    override fun seekBack() = seekToAbsolute(absolutePositionSec() - skipBackSec)

    override fun seekForward() = seekToAbsolute(absolutePositionSec() + skipForwardSec)

    override fun getSeekBackIncrement(): Long = (skipBackSec * 1000).toLong()

    override fun getSeekForwardIncrement(): Long = (skipForwardSec * 1000).toLong()
}
