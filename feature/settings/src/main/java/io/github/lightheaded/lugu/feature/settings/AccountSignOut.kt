package io.github.lightheaded.lugu.feature.settings

import io.github.lightheaded.lugu.core.download.DownloadRepository
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.AuthRepository
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The one way to sign out of an account, for Settings and for Accounts alike.
 *
 * `:core:sync` cannot reach `:core:download`, so [AuthRepository.signOutOf] cannot take the
 * downloaded audio with it, and its KDoc says the caller must. Two callers each doing that
 * step is how Settings came to skip it and leave the files on disk, unreachable and still
 * counted against the storage cap. The order matters: the download rows carry the server
 * id, so they have to go before the purge that removes the account.
 */
class AccountSignOut internal constructor(
    private val authRepository: AuthRepository,
    private val removeDownloads: suspend (serverId: String, userId: String) -> Unit,
) {
    @Inject
    constructor(authRepository: AuthRepository, downloadRepository: DownloadRepository) : this(
        authRepository,
        { serverId, userId -> downloadRepository.removeAllFor(serverId, userId) },
    )

    /**
     * Runs to its end once it starts, also when the screen that asked goes away: a
     * sign-out that stops between the two steps leaves an account with no downloads.
     */
    suspend fun signOutOf(account: ActiveAccount): Result<Unit> = withContext(NonCancellable) {
        // A download that cannot be removed stops the sign-out: the purge after it would
        // leave its bytes with nothing that points at them.
        runCatching { removeDownloads(account.serverId, account.userId) }
            .fold(
                onSuccess = { authRepository.signOutOf(account.serverId) },
                onFailure = { Result.failure(it) },
            )
    }

    /** Signs out of the account in use. With none in use there is nothing to do. */
    suspend fun signOutOfActive(): Result<Unit> {
        val account = runCatching { authRepository.account() }
            .getOrElse { return Result.failure(it) }
            ?: return Result.success(Unit)
        return signOutOf(account)
    }
}
