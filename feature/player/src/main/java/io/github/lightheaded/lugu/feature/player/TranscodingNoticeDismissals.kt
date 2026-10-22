package io.github.lightheaded.lugu.feature.player

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The items whose transcoding note the listener has put away, for the life of the process.
 *
 * The note is true for as long as the stream is, so it does not take itself away. But a fact
 * that has been read once does not need to keep the top of the screen. The player's view
 * model belongs to one visit of the player, so a dismissal held there came back on the next
 * visit. This holder is a singleton, so the dismissal lasts until the process ends. It is not
 * written to prefs: a new process can be a new stream, and then the note is news again.
 *
 * Keyed on the item and the episode, because the next book or episode is a different stream,
 * and the person who put this one away has not been asked about it.
 */
@Singleton
class TranscodingNoticeDismissals @Inject constructor() {

    private val dismissed = MutableStateFlow<Set<Key>>(emptySet())

    /** The keys of every note put away so far. */
    val keys: StateFlow<Set<Key>> = dismissed.asStateFlow()

    fun dismiss(libraryItemId: String, episodeId: String?) {
        dismissed.update { it + Key(libraryItemId, episodeId) }
    }

    data class Key(val libraryItemId: String, val episodeId: String?)
}
