package io.github.lightheaded.lugu.widget

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.lightheaded.lugu.core.db.InProgressRow
import io.github.lightheaded.lugu.core.db.LibraryItemDao
import io.github.lightheaded.lugu.core.sync.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * One row, turned into the two numbers the widget draws.
 *
 * Its own object so that a test can reach it. Glance renders through `RemoteViews` and has
 * no host outside a real launcher, so the arithmetic is the only part of the widget a test
 * can check — and both numbers have a failure a launcher would show and nobody could
 * explain. See `WidgetStateTest`.
 */
internal object WidgetMapping {
    fun toItem(row: InProgressRow): WidgetItem = WidgetItem(
        libraryItemId = row.item.id,
        title = row.item.title,
        author = row.item.authorName,
        // Clamped, because a duration the server has since corrected downwards leaves a
        // stored position past the end. A fraction over one draws as a full bar on some
        // launchers and as nothing on others, and the obvious "time left" goes negative —
        // a widget reading "-4 min left" gets reported as corruption.
        progressFraction = row.progressFraction.toFloat().coerceIn(0f, 1f),
        remainingSeconds = (row.playedDurationSec - row.positionSec).coerceAtLeast(0.0),
    )
}

/**
 * The one book the widget shows.
 *
 * The same query the Continue shelf reads, with a limit of one. Not a second definition of
 * "what to carry on with": a widget that disagreed with the shelf under it would be
 * reported as the app losing a book, and there would be two orderings to keep in step.
 */
@Singleton
class WidgetState @Inject constructor(
    private val authRepository: AuthRepository,
    private val libraryItemDao: LibraryItemDao,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun items(): Flow<WidgetItem?> = authRepository.observeAccount()
        .flatMapLatest { account ->
            if (account == null) {
                flowOf(emptyList())
            } else {
                libraryItemDao.observeInProgress(account.serverId, account.userId, limit = 1)
            }
        }
        .map { rows -> rows.firstOrNull()?.let(WidgetMapping::toItem) }

    /**
     * Reached through an entry point, because an app widget receiver is a
     * `BroadcastReceiver` the system builds and Hilt cannot inject into one of those.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Access {
        fun widgetState(): WidgetState
    }

    companion object {
        fun from(context: Context): WidgetState =
            EntryPointAccessors.fromApplication(context, Access::class.java).widgetState()
    }
}
