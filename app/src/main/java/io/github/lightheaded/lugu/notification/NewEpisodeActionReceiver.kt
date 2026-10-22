package io.github.lightheaded.lugu.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.lightheaded.lugu.core.download.NewEpisodeAction
import io.github.lightheaded.lugu.core.download.NewEpisodeNotifier
import io.github.lightheaded.lugu.core.sync.AuthRepository
import io.github.lightheaded.lugu.core.sync.QueueRepository
import io.github.lightheaded.lugu.playback.PlaybackConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Play and Add to queue buttons on a new-episode notification.
 *
 * Both go through the same entry points the app's own screens use: Play is
 * [PlaybackConnection.play] with the episode's two ids, exactly what a tap on an episode's
 * play button does, and Add to queue is [QueueRepository.addLast], exactly what the
 * episode page's menu does. Nothing here decides anything those two do not already decide.
 *
 * Not the automation receiver, although the shape is borrowed from it. That one is
 * exported and public, and it carries transport commands only; adding to the queue changes
 * the library, and playing an episode by id is not a verb the automation contract offers.
 * This receiver is not exported, so only a pending intent lugu made itself can reach it.
 *
 * An action is dropped when the account active now is not the one the episode was found
 * in: after a switch of server, the ids on the notification name nothing in this library.
 *
 * The dependencies are read through an entry point rather than `@AndroidEntryPoint`, for
 * the reason `AutomationReceiver` gives: Hilt's receiver support needs `super.onReceive`,
 * which Kotlin refuses to call on an abstract member.
 */
class NewEpisodeActionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Dependencies {
        fun authRepository(): AuthRepository
        fun playbackConnection(): PlaybackConnection
        fun queueRepository(): QueueRepository
        fun newEpisodeNotifier(): NewEpisodeNotifier
    }

    /** Outlives the receiver on purpose; the broadcast is held open while it works. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onReceive(context: Context, intent: Intent) {
        val action = NewEpisodeAction.from(intent) ?: return
        val deps = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java)
        }.getOrNull() ?: return

        val pending = goAsync()
        scope.launch {
            try {
                // An exception out of a receiver takes the process with it, and a book may
                // be playing in that process.
                runCatching { carryOut(deps, action) }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun carryOut(deps: Dependencies, action: NewEpisodeAction) {
        // Bounded, because Android kills a receiver that holds its broadcast for ten
        // seconds, and a database that does not answer must not cost the process.
        val account = withTimeoutOrNull(TIMEOUT_MS) { deps.authRepository().account() } ?: return
        if (!action.isFor(account)) return

        when (action.kind) {
            NewEpisodeAction.Kind.PLAY ->
                deps.playbackConnection().play(action.libraryItemId, action.episodeId)
            NewEpisodeAction.Kind.ENQUEUE ->
                withTimeoutOrNull(TIMEOUT_MS) {
                    deps.queueRepository().addLast(account, action.libraryItemId, action.episodeId)
                } ?: return
        }
        // The row has done its job. Leaving it would invite a second press, which for
        // Add to queue looks like nothing happened the first time.
        deps.newEpisodeNotifier().dismiss(action.notificationId)
    }

    private companion object {
        const val TIMEOUT_MS = 4_000L
    }
}
