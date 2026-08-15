package io.github.lightheaded.lugu.feature.player

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.playback.PlaybackConnection
import javax.inject.Inject

/**
 * Thin adapter over [PlaybackConnection].
 *
 * The player screen and the mini player share one instance of the connection, so both
 * report the same position and the same now-playing item — and neither of them owns
 * playback state, which keeps the service authoritative even while no UI is alive.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val connection: PlaybackConnection,
) : ViewModel() {

    val state = connection.state
    val nowPlaying = connection.nowPlaying
    val pendingJump = connection.pendingJump
    val rewindNotice = connection.rewindNotice
    val continuation = connection.continuation
    val sleepTimer = connection.sleepTimer
    val positionHistory = connection.observePositionHistory()
    val settings = connection.settings

    fun play(itemId: String, episodeId: String?) = connection.play(itemId, episodeId)

    fun togglePlayPause() = connection.togglePlayPause()

    fun seekBy(deltaSec: Double) = connection.seekBy(deltaSec)

    fun seekTo(absoluteSec: Double) = connection.seekTo(absoluteSec)

    fun setSpeed(speed: Float) = connection.setSpeed(speed)

    fun previousChapter() = connection.previousChapter()

    fun nextChapter() = connection.nextChapter()

    fun setSleepTimer(mode: io.github.lightheaded.lugu.core.model.SleepMode?) =
        connection.setSleepTimer(mode)

    fun extendSleepTimer(minutes: Int) = connection.extendSleepTimer(minutes)

    fun restorePosition(toSec: Double) = connection.restorePosition(toSec)

    val sleepPresets = io.github.lightheaded.lugu.core.model.SleepTimer.PRESET_MINUTES


    fun undoJump() = connection.undoJump()

    fun dismissJump() = connection.dismissJump()

    fun dismissRewindNotice() = connection.dismissRewindNotice()

    fun dismissContinuationNotice() = connection.dismissContinuationNotice()
}
