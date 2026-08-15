package io.github.lightheaded.lugu.playback

import io.github.lightheaded.lugu.core.model.Chapter
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.LibraryRepository
import io.github.lightheaded.lugu.core.sync.NextUp
import io.github.lightheaded.lugu.core.sync.QueuePrefs
import io.github.lightheaded.lugu.core.sync.QueueRepository
import javax.inject.Inject
import javax.inject.Singleton

/** What follows the thing that just ended, and whether to start it. */
data class Continuation(
    val resumption: Resumption,
    val reason: String?,
    /** False when the listener asked to be consulted before something new begins. */
    val autoStart: Boolean,
)

/** Injected so the service does not need to know what a queue is. */
interface ContinuationResolver {
    suspend fun resolveNext(finishedItemId: String, finishedEpisodeId: String?): Continuation?
}

/**
 * End of book, and no UI necessarily alive to decide what happens next.
 *
 * This runs in the playback service for the same reason resumption does: the moment it
 * matters most is in a car, where the app is not on screen and may not be in memory.
 * Everything it needs — the queue, the series, the episode list — is in Room, so the
 * decision is made offline and only the playable URLs may need the network.
 */
@Singleton
class DefaultContinuationResolver @Inject constructor(
    private val authRepository: AuthRepository,
    private val queueRepository: QueueRepository,
    private val queuePrefs: QueuePrefs,
    private val mediaResolver: MediaResolver,
    private val libraryRepository: LibraryRepository,
    private val stateHolder: PlaybackStateHolder,
) : ContinuationResolver {

    override suspend fun resolveNext(finishedItemId: String, finishedEpisodeId: String?): Continuation? {
        val account = authRepository.account() ?: return null
        val next = queueRepository.next(account, finishedItemId, finishedEpisodeId) ?: return null

        val (item, reason, isSuggestion) = when (next) {
            is NextUp.Queued -> Triple(next.item, null, false)
            is NextUp.Suggested -> Triple(next.item, next.reason, true)
        }

        val resolved = mediaResolver.resolve(account, item.libraryItemId, item.episodeId).getOrNull() ?: return null

        val chapters = runCatching {
            libraryRepository.chapters(account, item.libraryItemId).map {
                Chapter(it.chapterIndex, it.startSec, it.endSec, it.title)
            }
        }.getOrDefault(emptyList())

        val nowPlaying = NowPlaying(
            libraryItemId = item.libraryItemId,
            episodeId = item.episodeId,
            title = resolved.session.title,
            author = resolved.session.author,
            coverUrl = runCatching { libraryRepository.coverUrl(item.libraryItemId, 600) }.getOrNull(),
            durationSec = resolved.session.durationSec,
            tracks = resolved.session.tracks,
            chapters = chapters.ifEmpty { resolved.session.chapters },
            ledgerId = resolved.ledgerId,
            isTranscoded = resolved.session.isTranscoded,
        )

        val position = AbsoluteTiming.toTrack(resolved.session.tracks, resolved.startPositionSec)
        val askFirst = isSuggestion && queuePrefs.current().askBeforeSuggestion

        // A suggestion that is only being cued goes back to the head of the queue, so
        // declining the prompt does not throw the answer away and the queue screen shows
        // what lugu was about to do.
        if (askFirst) queueRepository.offer(account, item)
        stateHolder.setContinuationNotice(reason?.takeIf { isSuggestion }, cued = askFirst)

        return Continuation(
            resumption = Resumption(
                mediaItems = resolved.mediaItems,
                startTrackIndex = position.trackIndex,
                startPositionMs = position.positionMs,
                nowPlaying = nowPlaying,
            ),
            reason = reason,
            autoStart = !askFirst,
        )
    }
}
