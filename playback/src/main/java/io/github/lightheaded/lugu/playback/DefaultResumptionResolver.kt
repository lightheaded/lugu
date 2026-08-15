package io.github.lightheaded.lugu.playback

import io.github.lightheaded.lugu.core.db.ProgressDao
import io.github.lightheaded.lugu.core.db.toEpisodeIdOrNull
import io.github.lightheaded.lugu.core.model.Chapter
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.LibraryRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rebuilds "what was I listening to" without any UI alive.
 *
 * The answer comes from Room — the most recently updated unfinished progress row —
 * so it survives process death and reboot. Only the playable URLs need the network,
 * because a play session has to be opened to learn them.
 */
@Singleton
class DefaultResumptionResolver @Inject constructor(
    private val authRepository: AuthRepository,
    private val progressDao: ProgressDao,
    private val mediaResolver: MediaResolver,
    private val libraryRepository: LibraryRepository,
    private val stateHolder: PlaybackStateHolder,
) : ResumptionResolver {

    override suspend fun resolveLastPlayed(): Resumption? {
        val account = authRepository.account() ?: return null
        val last = progressDao.mostRecent(account.serverId, account.userId) ?: return null
        return resolve(last.libraryItemId, last.episodeKey.toEpisodeIdOrNull())
    }

    /**
     * One item, resolved into something a player can take.
     *
     * Shared by resumption, by the end-of-book continuation and by anything a car asks
     * to play, because all three arrive with nothing but an id and none of them can
     * count on a screen being alive to fill in the rest.
     */
    override suspend fun resolve(itemId: String, episodeId: String?): Resumption? {
        val account = authRepository.account() ?: return null
        val resolved = mediaResolver.resolve(account, itemId, episodeId).getOrNull() ?: return null

        val chapters = runCatching {
            libraryRepository.chapters(account, itemId).map {
                Chapter(it.chapterIndex, it.startSec, it.endSec, it.title)
            }
        }.getOrDefault(emptyList())

        val nowPlaying = NowPlaying(
            libraryItemId = itemId,
            episodeId = episodeId,
            title = resolved.session.title,
            author = resolved.session.author,
            coverUrl = runCatching { libraryRepository.coverUrl(itemId, 600) }.getOrNull(),
            durationSec = resolved.session.durationSec,
            tracks = resolved.session.tracks,
            chapters = chapters.ifEmpty { resolved.session.chapters },
            ledgerId = resolved.ledgerId,
            isTranscoded = resolved.session.isTranscoded,
        )
        stateHolder.setJump(resolved.jump)

        val position = AbsoluteTiming.toTrack(resolved.session.tracks, resolved.startPositionSec)
        return Resumption(
            mediaItems = resolved.mediaItems,
            startTrackIndex = position.trackIndex,
            startPositionMs = position.positionMs,
            nowPlaying = nowPlaying,
        )
    }
}
