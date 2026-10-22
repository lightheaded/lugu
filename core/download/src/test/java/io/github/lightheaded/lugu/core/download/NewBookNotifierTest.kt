package io.github.lightheaded.lugu.core.download

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.NewBook
import io.github.lightheaded.lugu.core.sync.NewEpisode
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What a new-book notification shows and what its buttons send.
 *
 * The rows come from the same notifier as the episode rows, so these check what differs:
 * the words, the missing episode id, and a group and channel of their own.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NewBookNotifierTest {

    private lateinit var application: Application
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager = application.getSystemService(NotificationManager::class.java)
        // A library module has no launcher activity, and the tap is built on the launch
        // intent. Give the package one, as the app has.
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(application.packageName)
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = application.packageName
                name = "io.github.lightheaded.lugu.LauncherForTest"
            }
        }
        shadowOf(application.packageManager).addResolveInfoForIntent(launcher, info)
    }

    @Test
    fun `one book is one row with its title, author and snippet`() {
        notifier().announce(account, listOf(first))

        val row = rowFor(first)
        assertThat(row.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            .isEqualTo("The Lantern Ferry")
        assertThat(row.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
            .isEqualTo("Ilse Varn")
        assertThat(row.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            .isEqualTo("Ilse Varn · A ferry that only runs at night.")
        assertThat(row.channelId).isEqualTo(NewEpisodeNotifier.BOOK_CHANNEL_ID)
        assertThat(row.group).isEqualTo(NewEpisodeNotifier.BOOK_GROUP_KEY)
        assertThat(row.groupAlertBehavior).isEqualTo(NotificationCompat.GROUP_ALERT_ALL)
        assertThat(manager.activeNotifications.map { it.id })
            .doesNotContain(NewEpisodeNotifier.BOOK_SUMMARY_ID)
    }

    @Test
    fun `a book with no author or description still says something`() {
        val bare = first.copy(authorName = null, description = null)
        val content = bookRowContent(bare)

        assertThat(content.title).isEqualTo("The Lantern Ferry")
        assertThat(content.podcast).isEmpty()
        assertThat(content.text).isEqualTo("New in your library")
    }

    @Test
    fun `a book row has Play and Add to queue, for the book and its account`() {
        notifier().announce(account, listOf(first))
        val row = rowFor(first)

        assertThat(row.actions.map { it.title.toString() }).containsExactly("Play", "Add to queue").inOrder()
        val sent = row.actions.map { action ->
            val pending = shadowOf(action.actionIntent)
            assertThat(pending.isBroadcastIntent).isTrue()
            assertThat(pending.savedIntent.component?.className).isEqualTo(NewEpisodeIntent.ACTION_RECEIVER)
            assertThat(pending.savedIntent.hasExtra(NewEpisodeIntent.EXTRA_EPISODE_ID)).isFalse()
            NewEpisodeAction.from(pending.savedIntent)
        }
        val expected = NewEpisodeAction(
            kind = NewEpisodeAction.Kind.PLAY,
            libraryItemId = "li_book_1",
            episodeId = null,
            serverId = "srv_a",
            userId = "usr_a",
            notificationId = notificationIdOf(first),
        )
        assertThat(sent).containsExactly(expected, expected.copy(kind = NewEpisodeAction.Kind.ENQUEUE)).inOrder()
    }

    @Test
    fun `a tap names the book and no episode`() {
        notifier().announce(account, listOf(first))

        val tap = shadowOf(rowFor(first).contentIntent).savedIntent
        assertThat(tap.getStringExtra(NewEpisodeIntent.EXTRA_LIBRARY_ITEM_ID)).isEqualTo("li_book_1")
        assertThat(tap.hasExtra(NewEpisodeIntent.EXTRA_EPISODE_ID)).isFalse()
    }

    @Test
    fun `two books go under one summary of their own`() {
        notifier().announce(account, listOf(second, first))

        val summary = shadowOf(manager).getNotification(NewEpisodeNotifier.BOOK_SUMMARY_ID)
        assertThat(summary.flags and Notification.FLAG_GROUP_SUMMARY).isNotEqualTo(0)
        assertThat(summary.group).isEqualTo(NewEpisodeNotifier.BOOK_GROUP_KEY)
        assertThat(summary.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            .isEqualTo("2 new books")
        assertThat(rowFor(first).groupAlertBehavior).isEqualTo(NotificationCompat.GROUP_ALERT_SUMMARY)
        // The summary opens the newest, which comes first.
        assertThat(shadowOf(summary.contentIntent).savedIntent.getStringExtra(NewEpisodeIntent.EXTRA_LIBRARY_ITEM_ID))
            .isEqualTo("li_book_2")
        assertThat(manager.activeNotifications.map { it.id }).doesNotContain(NewEpisodeNotifier.SUMMARY_ID)
    }

    @Test
    fun `books and episodes keep their groups apart`() {
        notifier().announce(account, listOf(first))
        notifier().notify(account, listOf(episode))

        // One of each is two lone rows, not a group of two.
        assertThat(manager.activeNotifications.map { it.id })
            .containsExactly(notificationIdOf(first), notificationIdOf(episode))
    }

    @Test
    fun `dismissing a book row takes its summary too once one row is left`() {
        notifier().announce(account, listOf(second, first))

        notifier().dismiss(notificationIdOf(first))

        assertThat(manager.activeNotifications.map { it.id }).containsExactly(notificationIdOf(second))
    }

    @Test
    fun `nothing is posted without the permission`() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        notifier().announce(account, listOf(first))

        assertThat(manager.activeNotifications).isEmpty()
    }

    private fun notifier() = NewEpisodeNotifier(application)

    private fun rowFor(book: NewBook): Notification = shadowOf(manager).getNotification(notificationIdOf(book))

    private val account = ActiveAccount(
        serverId = "srv_a",
        baseUrl = "https://abs.example.invalid",
        userId = "usr_a",
        username = "listener",
        defaultLibraryId = null,
    )

    private val first = NewBook(
        libraryItemId = "li_book_1",
        libraryId = "lib_books",
        title = "The Lantern Ferry",
        authorName = "Ilse Varn",
        description = "<p>A ferry that only runs at night.</p>",
        addedAtMs = 1_710_000_000_000L,
    )

    private val second = NewBook(
        libraryItemId = "li_book_2",
        libraryId = "lib_books",
        title = "Salt Roads",
        authorName = "Oren Maddock",
        description = null,
        addedAtMs = 1_711_000_000_000L,
    )

    private val episode = NewEpisode(
        libraryItemId = "li_pod",
        episodeId = "ep_14",
        podcastTitle = "Coastal Signal",
        episodeTitle = "A quiet hour on 500 kHz",
        publishedAtMs = 1_710_000_000_000L,
    )
}
