package io.github.lightheaded.lugu.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.CoroutineWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.lightheaded.lugu.core.api.AuthExpiredException
import io.github.lightheaded.lugu.core.db.OutboxDao
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Injectable time source, so sync rules can be tested without sleeping. */
interface Clock {
    fun nowMs(): Long
}

@Singleton
class WallClock @Inject constructor() : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/**
 * Drains the outbox.
 *
 * Entries that fail for a recoverable reason stay queued and are retried with backoff.
 * Entries the server will never accept are dropped rather than retried forever — a
 * poisoned entry blocking the queue would silently stop all later syncing.
 */
@Singleton
class OutboxFlusher @Inject constructor(
    private val outboxDao: OutboxDao,
    private val progressRepository: ProgressRepository,
    private val sessionLedgerRepository: SessionLedgerRepository,
    private val authRepository: AuthRepository,
    private val clock: Clock,
) {
    fun observeDepth(account: ActiveAccount): Flow<Int> =
        outboxDao.observeDepth(account.serverId, account.userId)

    suspend fun flush(): FlushResult {
        val account = authRepository.account() ?: return FlushResult.NoAccount
        val entries = outboxDao.peek(account.serverId, account.userId)
        if (entries.isEmpty()) {
            sessionLedgerRepository.uploadPending(account)
            return FlushResult.Empty
        }

        var failed = 0
        for (entry in entries) {
            val outcome = runCatching { progressRepository.flushEntry(entry) }
            when {
                outcome.isSuccess -> outboxDao.delete(entry.rowId)

                outcome.exceptionOrNull() is AuthExpiredException -> {
                    // Credentials are gone: retrying the rest now just burns battery.
                    outboxDao.recordFailure(entry.rowId, clock.nowMs(), "auth expired")
                    return FlushResult.AuthRequired
                }

                entry.attempts >= MAX_ATTEMPTS -> {
                    // Give up on this one so it cannot wedge everything behind it.
                    outboxDao.delete(entry.rowId)
                }

                else -> {
                    failed += 1
                    outboxDao.recordFailure(
                        entry.rowId,
                        clock.nowMs(),
                        outcome.exceptionOrNull()?.message?.take(200),
                    )
                }
            }
        }

        sessionLedgerRepository.uploadPending(account)
        return if (failed > 0) FlushResult.Retry(failed) else FlushResult.Flushed(entries.size)
    }

    companion object {
        const val MAX_ATTEMPTS = 12
    }
}

sealed interface FlushResult {
    data object NoAccount : FlushResult

    data object Empty : FlushResult

    data object AuthRequired : FlushResult

    data class Flushed(val count: Int) : FlushResult

    data class Retry(val failed: Int) : FlushResult
}

@HiltWorker
class OutboxWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val flusher: OutboxFlusher,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = when (flusher.flush()) {
        is FlushResult.Retry -> Result.retry()
        // Nothing to do and nothing we can fix by retrying.
        FlushResult.AuthRequired, FlushResult.NoAccount, FlushResult.Empty -> Result.success()
        is FlushResult.Flushed -> Result.success()
    }
}

/** Periodic reconciliation: re-pulls progress and re-mirrors libraries. */
@HiltWorker
class ReconcileWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val authRepository: AuthRepository,
    private val libraryRepository: LibraryRepository,
    private val progressRepository: ProgressRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val account = authRepository.account() ?: return Result.success()
        val libraries = libraryRepository.syncLibraries(account).getOrElse { return Result.retry() }
        libraries.forEach { libraryRepository.syncLibraryItems(account, it.id) }
        progressRepository.reconcile(account)
        return Result.success()
    }
}

object SyncScheduler {
    private const val OUTBOX_WORK = "lugu-outbox"
    private const val RECONCILE_WORK = "lugu-reconcile"

    fun flushNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(OUTBOX_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReconcileWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(RECONCILE_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
