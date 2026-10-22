package io.github.lightheaded.lugu.core.download

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.NewEpisode
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What a new-episode notification shows and what its buttons send.
 *
 * The buttons are read back through [NewEpisodeAction.from], the same parser the app's
 * receiver uses, so a key written under one name and read under another fails here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NewEpisodeNotifierTest {

    private lateinit var application: Application
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager = application.getSystemService(NotificationManager::class.java)
    }

    @Test
    fun `one episode is one row with its title, podcast and snippet`() {
        notifier().notify(account, listOf(first), mapOf(first.episodeId to "<p>Fog <i>horns</i>.</p>"))

        val row = rowFor(first)
        assertThat(row.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            .isEqualTo("A quiet hour on 500 kHz")
        assertThat(row.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
            .isEqualTo("Coastal Signal")
        assertThat(row.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            .endsWith(" · Fog horns.")
        assertThat(row.group).isEqualTo(NewEpisodeNotifier.GROUP_KEY)
        // Alone, it is not silenced for a summary that was never posted.
        assertThat(row.groupAlertBehavior).isEqualTo(NotificationCompat.GROUP_ALERT_ALL)
        assertThat(manager.activeNotifications.map { it.id })
            .doesNotContain(NewEpisodeNotifier.SUMMARY_ID)
    }

    @Test
    fun `a row has Play and Add to queue, in that order`() {
        notifier().notify(account, listOf(first))

        assertThat(rowFor(first).actions.map { it.title.toString() })
            .containsExactly("Play", "Add to queue").inOrder()
    }

    @Test
    fun `each button names its episode, its account and its row`() {
        notifier().notify(account, listOf(first))
        val row = rowFor(first)

        val sent = row.actions.map { action ->
            val pending = shadowOf(action.actionIntent)
            assertThat(pending.isBroadcastIntent).isTrue()
            assertThat(pending.savedIntent.component?.className)
                .isEqualTo(NewEpisodeIntent.ACTION_RECEIVER)
            assertThat(pending.savedIntent.component?.packageName).isEqualTo(application.packageName)
            NewEpisodeAction.from(pending.savedIntent)
        }

        val expected = NewEpisodeAction(
            kind = NewEpisodeAction.Kind.PLAY,
            libraryItemId = "li_pod",
            episodeId = "ep_14",
            serverId = "srv_a",
            userId = "usr_a",
            notificationId = notificationIdOf(first),
        )
        assertThat(sent).containsExactly(expected, expected.copy(kind = NewEpisodeAction.Kind.ENQUEUE))
            .inOrder()
    }

    @Test
    fun `two rows' buttons are two different pending intents`() {
        notifier().notify(account, listOf(first, second))

        val firstPlay = NewEpisodeAction.from(shadowOf(rowFor(first).actions[0].actionIntent).savedIntent)
        val secondPlay = NewEpisodeAction.from(shadowOf(rowFor(second).actions[0].actionIntent).savedIntent)
        assertThat(firstPlay?.episodeId).isEqualTo("ep_14")
        assertThat(secondPlay?.episodeId).isEqualTo("ep_7")
    }

    @Test
    fun `a batch posts its rows under one summary, and only the summary alerts`() {
        notifier().notify(account, listOf(first, second))

        val summary = shadowOf(manager).getNotification(NewEpisodeNotifier.SUMMARY_ID)
        assertThat(summary.flags and Notification.FLAG_GROUP_SUMMARY).isNotEqualTo(0)
        assertThat(summary.group).isEqualTo(NewEpisodeNotifier.GROUP_KEY)
        assertThat(summary.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            .isEqualTo("2 new episodes")
        assertThat(rowFor(first).groupAlertBehavior).isEqualTo(NotificationCompat.GROUP_ALERT_SUMMARY)
        assertThat(rowFor(second).groupAlertBehavior).isEqualTo(NotificationCompat.GROUP_ALERT_SUMMARY)
    }

    @Test
    fun `a large batch posts rows for the newest five and counts the rest`() {
        val batch = (1..8).map {
            first.copy(episodeId = "ep_b$it", episodeTitle = "Part $it", publishedAtMs = 2_000L - it)
        }
        notifier().notify(account, batch)

        val rows = manager.activeNotifications.filter { it.id != NewEpisodeNotifier.SUMMARY_ID }
        assertThat(rows.map { it.id }).containsExactlyElementsIn(batch.take(5).map(::notificationIdOf))
        val summary = shadowOf(manager).getNotification(NewEpisodeNotifier.SUMMARY_ID)
        assertThat(summary.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT).toString())
            .isEqualTo("and 3 more")
    }

    @Test
    fun `a new row beside one from an earlier batch brings the summary`() {
        notifier().notify(account, listOf(first))
        notifier().notify(account, listOf(second))

        assertThat(manager.activeNotifications.map { it.id })
            .contains(NewEpisodeNotifier.SUMMARY_ID)
    }

    @Test
    fun `dismissing a row takes the summary too once one row is left`() {
        notifier().notify(account, listOf(first, second))

        notifier().dismiss(notificationIdOf(first))

        assertThat(manager.activeNotifications.map { it.id })
            .containsExactly(notificationIdOf(second))
    }

    @Test
    fun `a broadcast that is not complete is not an action`() {
        assertThat(NewEpisodeAction.from(null)).isNull()
        assertThat(NewEpisodeAction.from(android.content.Intent("something.else"))).isNull()
        assertThat(
            NewEpisodeAction.from(
                android.content.Intent(NewEpisodeIntent.ACTION_PLAY)
                    .putExtra(NewEpisodeIntent.EXTRA_LIBRARY_ITEM_ID, "li_pod")
                    .putExtra(NewEpisodeIntent.EXTRA_EPISODE_ID, "ep_14"),
            ),
        ).isNull()
    }

    @Test
    fun `an action is only for the account it was posted under`() {
        val action = NewEpisodeAction(
            NewEpisodeAction.Kind.ENQUEUE, "li_pod", "ep_14", "srv_a", "usr_a", 1,
        )
        assertThat(action.isFor(account)).isTrue()
        assertThat(action.isFor(account.copy(userId = "usr_b"))).isFalse()
        assertThat(action.isFor(account.copy(serverId = "srv_b"))).isFalse()
    }

    @Test
    fun `nothing is posted without the permission`() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifier().notify(account, listOf(first))

        assertThat(manager.activeNotifications).isEmpty()
    }

    private fun notifier() = NewEpisodeNotifier(application)

    private fun rowFor(episode: NewEpisode): Notification =
        shadowOf(manager).getNotification(notificationIdOf(episode))

    private val account = ActiveAccount(
        serverId = "srv_a",
        baseUrl = "https://abs.example.invalid",
        userId = "usr_a",
        username = "listener",
        defaultLibraryId = null,
    )

    private val first = NewEpisode(
        libraryItemId = "li_pod",
        episodeId = "ep_14",
        podcastTitle = "Coastal Signal",
        episodeTitle = "A quiet hour on 500 kHz",
        publishedAtMs = 1_710_000_000_000L,
    )

    private val second = NewEpisode(
        libraryItemId = "li_pod_2",
        episodeId = "ep_7",
        podcastTitle = "Harbour Notes",
        episodeTitle = "Ferry timetables as numbers stations",
        publishedAtMs = 1_709_000_000_000L,
    )
}
