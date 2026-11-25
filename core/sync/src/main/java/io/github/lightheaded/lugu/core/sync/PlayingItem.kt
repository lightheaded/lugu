package io.github.lightheaded.lugu.core.sync

import io.github.lightheaded.lugu.core.model.ProgressKey
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The item in the player, for the code in this module that writes the server's positions.
 *
 * A sweep must leave the playing item alone: its position moves only through a session
 * start, which can seek the player and show the jump. A sweep that wrote under it put the
 * server's copy in Room, dropped the item's push and wrote a false "From another device"
 * entry, and the next tick then put the position back. See [ProgressRepository.seedFromServer].
 *
 * The player lives in the playback module, which this module cannot see, so the
 * application sets this. [RealtimeSync.setNowPlaying] is the one place that does.
 */
@Singleton
class PlayingItem @Inject constructor() {
    private val key = AtomicReference<ProgressKey?>(null)

    /** What the player is on, or null when nothing is loaded. */
    fun get(): ProgressKey? = key.get()

    fun set(libraryItemId: String?, episodeId: String?) {
        key.set(libraryItemId?.let { ProgressKey(it, episodeId) })
    }
}
