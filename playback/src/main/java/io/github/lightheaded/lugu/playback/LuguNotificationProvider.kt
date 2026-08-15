package io.github.lightheaded.lugu.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/**
 * The notification, built from the listener's buttons rather than Media3's.
 *
 * Only one method is replaced, and it is the one that decides which buttons exist. The
 * default implementation fills the two places either side of play-pause with its own
 * previous/next buttons whenever the app has not claimed them — so a listener who asked
 * for one button got two, and a listener who asked for none got the pair back. Neither is
 * what the settings screen promises, and neither can be fixed by handing the provider a
 * better list.
 *
 * Everything else about the notification — the artwork, the metadata, the dismissal
 * intent, the compact-view choice — is left to the default, which does it well.
 *
 * ## Why the progress bar is not chapter-scoped
 *
 * There is no setting for it, and this is the reason. It cannot be honoured without lying
 * about the player's position, and the lie cannot be confined to the notification.
 *
 * The bar the system draws is not part of this notification at all. It comes from the
 * platform media session's playback state, whose position is
 * `MediaSession.getPlayer().getCurrentPosition()` and whose duration is the current media
 * item's — one shared pair of numbers, rebuilt from the session player on player events.
 * Every other surface reads that same pair: the app's own `MediaController`, and therefore
 * the player screen and the scrubber, and a car's transport and its seek bar. There is no
 * per-controller position and no hook that runs only on the way to the notification.
 *
 * A forwarding player that reported chapter-relative numbers would also have to keep them
 * coherent with the timeline it forwards unchanged, with the seeks it is given in
 * whole-book or per-track terms, and with the position discontinuity events the real
 * player emits with real positions — which controllers use to correct their own
 * interpolated position between updates. It would not be a display change; it would be a
 * second, disagreeing notion of where the book is, in the one place [AbsoluteTiming] and
 * [ChapterAwarePlayer] exist to keep single. That is how a resumed book starts in the
 * wrong chapter, and it is not worth a bar that moves.
 *
 * The chapter is still said out loud where it is free to say it: the notification's text
 * carries the chapter title, which answers "where am I" without claiming to be a position.
 */
@OptIn(UnstableApi::class)
class LuguNotificationProvider(context: Context) : DefaultMediaNotificationProvider(context) {

    /**
     * The notification's buttons: whatever the session offered, and nothing invented.
     *
     * The offered buttons already carry their resolved slots, so the first one asking for
     * the place before play-pause gets it, the first one asking for the place after gets
     * that, and the rest go to the overflow the expanded notification shows. Order within
     * each group is the order the listener chose.
     */
    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        showPauseButton: Boolean,
    ): ImmutableList<CommandButton> {
        // A button whose command the notification controller may not send would render as
        // a control that does nothing, which is worse than one place fewer.
        val offered = mediaButtonPreferences.filter { it.isEnabled && it.sessionCommand != null }
        val back = offered.firstOrNull { it.slots.contains(CommandButton.SLOT_BACK) }
        val forward = offered.firstOrNull {
            it !== back && it.slots.contains(CommandButton.SLOT_FORWARD)
        }

        return ImmutableList.copyOf(
            buildList {
                back?.let(::add)
                if (playerCommands.contains(Player.COMMAND_PLAY_PAUSE)) add(playPause(showPauseButton))
                forward?.let(::add)
                offered.filterTo(this) { it !== back && it !== forward }
            },
        )
    }

    /**
     * Play-pause is built here rather than taken from the settings because it is the one
     * button that is never optional: a notification that cannot stop the audio is a
     * notification the listener has to open the app to escape.
     */
    private fun playPause(showPauseButton: Boolean): CommandButton = CommandButton.Builder(
        if (showPauseButton) CommandButton.ICON_PAUSE else CommandButton.ICON_PLAY,
    )
        .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
        .setDisplayName(if (showPauseButton) "Pause" else "Play")
        .build()
}
