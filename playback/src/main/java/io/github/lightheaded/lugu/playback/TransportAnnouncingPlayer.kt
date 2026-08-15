package io.github.lightheaded.lugu.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Reports transport commands as they arrive, before they reach the player.
 *
 * Every controller — the app, the notification, the lock screen, a headset, Android Auto —
 * reaches the player through the session, and therefore through here. That makes this the
 * one place where "somebody asked for this" can be known as a fact rather than inferred
 * afterwards from the player's state.
 *
 * Media3 already attaches a reason to most `playWhenReady` changes, and that reason is
 * usually enough. This exists for the two cases it is not: `stop()`, which leaves the
 * player idle and would otherwise be indistinguishable from a failure, and telling an
 * explicit play apart from an automatic one so a route-loss resume is not left armed.
 */
@OptIn(UnstableApi::class)
class TransportAnnouncingPlayer(
    player: Player,
    private val onPlayRequested: () -> Unit,
    private val onPauseRequested: () -> Unit,
    private val onStopRequested: () -> Unit,
) : ForwardingPlayer(player) {

    override fun play() {
        onPlayRequested()
        super.play()
    }

    override fun pause() {
        onPauseRequested()
        super.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) onPlayRequested() else onPauseRequested()
        super.setPlayWhenReady(playWhenReady)
    }

    override fun stop() {
        onStopRequested()
        super.stop()
    }
}
