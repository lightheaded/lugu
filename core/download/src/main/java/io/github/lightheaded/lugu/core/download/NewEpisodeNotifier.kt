package io.github.lightheaded.lugu.core.download

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.lightheaded.lugu.core.model.parseShowNotes
import io.github.lightheaded.lugu.core.sync.ActiveAccount
import io.github.lightheaded.lugu.core.sync.NewBook
import io.github.lightheaded.lugu.core.sync.NewBookAnnouncer
import io.github.lightheaded.lugu.core.sync.NewEpisode
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The extras and actions a new-episode or new-book notification carries.
 *
 * A book has no episode id. Its intents leave that extra out, and the item id alone names
 * what to play, queue or open.
 *
 * Declared here, beside the code that writes them, rather than in `:app`: this module
 * cannot see the app module, and a pair of string keys copied into two places is a pair of
 * string keys that will disagree one day.
 */
object NewEpisodeIntent {
    const val EXTRA_LIBRARY_ITEM_ID = "io.github.lightheaded.lugu.extra.LIBRARY_ITEM_ID"
    const val EXTRA_EPISODE_ID = "io.github.lightheaded.lugu.extra.EPISODE_ID"

    /** Which account the episode belongs to, so an action never lands in another one. */
    const val EXTRA_SERVER_ID = "io.github.lightheaded.lugu.extra.SERVER_ID"
    const val EXTRA_USER_ID = "io.github.lightheaded.lugu.extra.USER_ID"

    /** The notification an action came from, so the receiver can take it away once done. */
    const val EXTRA_NOTIFICATION_ID = "io.github.lightheaded.lugu.extra.NOTIFICATION_ID"

    /**
     * Not under `${applicationId}.action.*` on purpose: those names are the public
     * automation contract (`docs/automation.md`), and these two are not part of it. They go
     * to a receiver that is not exported, addressed by its class, so nothing outside lugu
     * can send them.
     */
    const val ACTION_PLAY = "io.github.lightheaded.lugu.notification.PLAY_EPISODE"
    const val ACTION_ENQUEUE = "io.github.lightheaded.lugu.notification.ENQUEUE_EPISODE"

    /**
     * The app module's receiver for the two actions, by name because this module cannot
     * see the class. `NewEpisodeActionReceiverTest` in `:app` holds this string to the real
     * class name, so a rename or a move fails a test rather than a tap.
     */
    const val ACTION_RECEIVER = "io.github.lightheaded.lugu.notification.NewEpisodeActionReceiver"
}

/** What a Play or Add-to-queue press asks for, read back from the broadcast. */
data class NewEpisodeAction(
    val kind: Kind,
    val libraryItemId: String,
    /** Null for a book: the action is for the item itself. */
    val episodeId: String?,
    val serverId: String,
    val userId: String,
    val notificationId: Int,
) {
    enum class Kind { PLAY, ENQUEUE }

    /**
     * Whether the account active now is the one the episode was found in. Someone who
     * switched server since the notification was posted has a different library, and its
     * ids mean nothing there.
     */
    fun isFor(account: ActiveAccount): Boolean =
        account.serverId == serverId && account.userId == userId

    companion object {
        /**
         * Null for anything that is not a complete action: an unknown verb, or a missing
         * item or account id. A missing episode id is a book, not a fault.
         */
        fun from(intent: Intent?): NewEpisodeAction? {
            intent ?: return null
            val kind = when (intent.action) {
                NewEpisodeIntent.ACTION_PLAY -> Kind.PLAY
                NewEpisodeIntent.ACTION_ENQUEUE -> Kind.ENQUEUE
                else -> return null
            }
            fun text(key: String) = intent.getStringExtra(key)?.takeIf { it.isNotBlank() }
            return NewEpisodeAction(
                kind = kind,
                libraryItemId = text(NewEpisodeIntent.EXTRA_LIBRARY_ITEM_ID) ?: return null,
                episodeId = text(NewEpisodeIntent.EXTRA_EPISODE_ID),
                serverId = text(NewEpisodeIntent.EXTRA_SERVER_ID) ?: return null,
                userId = text(NewEpisodeIntent.EXTRA_USER_ID) ?: return null,
                notificationId = intent.getIntExtra(NewEpisodeIntent.EXTRA_NOTIFICATION_ID, NO_ID),
            )
        }

        const val NO_ID = -1
    }
}

/**
 * Says when a podcast being listened to has published something.
 *
 * ### One notification per episode, in one group
 *
 * Each episode gets its own notification, because each one carries its own Play and Add
 * to queue buttons, and a button can act on one episode only. They share a group, and a
 * batch of two or more also posts a group summary. Android draws a group as one entry in
 * the shade that opens into its rows, which is what keeps eleven new episodes from
 * becoming eleven separate interruptions — the reason the old single-notification design
 * had. Only the summary alerts in a batch; the rows arrive silently under it.
 *
 * A batch posts rows for the [MAX_ROWS] newest only. The summary still counts the rest,
 * and all of them are on Home, so nothing is hidden; a shade of twenty rows is a shade
 * somebody clears without reading.
 *
 * Off by default, like every rule that runs on its own.
 */
@Singleton
class NewEpisodeNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : NewBookAnnouncer {
    /**
     * Posts the batch. [descriptions] maps an episode id to its feed description, which
     * becomes the snippet; an episode missing from it simply has none.
     */
    // allowed() checks POST_NOTIFICATIONS first, and runCatching covers a revoke in between.
    @SuppressLint("MissingPermission")
    fun notify(
        account: ActiveAccount,
        episodes: List<NewEpisode>,
        descriptions: Map<String, String?> = emptyMap(),
    ) {
        if (episodes.isEmpty()) return
        if (!allowed()) return

        ensureChannel(CHANNEL_ID, "New episodes")
        val manager = NotificationManagerCompat.from(context)
        val rows = episodes.take(MAX_ROWS)
        val withSummary = episodes.size > 1 || otherRowsShowing(GROUP_KEY, rows.map(::notificationIdOf))

        for (episode in rows) {
            val id = notificationIdOf(episode)
            val notification = row(account, episode, descriptions[episode.episodeId], id, withSummary)
            runCatching { manager.notify(id, notification) }
        }
        if (withSummary) {
            runCatching { manager.notify(SUMMARY_ID, summary(episodes)) }
        }
    }

    /**
     * Posts the new books from a library sync, by the same rules as episodes: one row per
     * book with Play and Add to queue, a group of their own, and a summary for two or
     * more. Their own channel too, so somebody can silence one kind and keep the other.
     *
     * [books] arrive newest first. The setting is checked by the caller, in the sync.
     */
    // allowed() checks POST_NOTIFICATIONS first, and runCatching covers a revoke in between.
    @SuppressLint("MissingPermission")
    fun notifyBooks(account: ActiveAccount, books: List<NewBook>) {
        if (books.isEmpty()) return
        if (!allowed()) return

        ensureChannel(BOOK_CHANNEL_ID, "New books")
        val manager = NotificationManagerCompat.from(context)
        val rows = books.take(MAX_ROWS)
        val withSummary = books.size > 1 || otherRowsShowing(BOOK_GROUP_KEY, rows.map(::notificationIdOf))

        for (book in rows) {
            val id = notificationIdOf(book)
            runCatching { manager.notify(id, bookRow(account, book, id, withSummary)) }
        }
        if (withSummary) {
            runCatching { manager.notify(BOOK_SUMMARY_ID, bookSummary(books)) }
        }
    }

    override fun announce(account: ActiveAccount, books: List<NewBook>) = notifyBooks(account, books)

    /**
     * Takes one row away after its action has been carried out, and the summary of its
     * group with it when fewer than two rows are left: a group of one needs no heading.
     */
    fun dismiss(notificationId: Int) {
        if (notificationId == NewEpisodeAction.NO_ID) return
        val manager = NotificationManagerCompat.from(context)
        runCatching { manager.cancel(notificationId) }
        for ((group, summaryId) in listOf(GROUP_KEY to SUMMARY_ID, BOOK_GROUP_KEY to BOOK_SUMMARY_ID)) {
            if (activeRowIds(group, summaryId).count { it != notificationId } < 2) {
                runCatching { manager.cancel(summaryId) }
            }
        }
    }

    /**
     * Below API 33 the permission does not exist and notifications are allowed by
     * default; above it, being refused is an answer to respect silently.
     */
    private fun allowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun row(
        account: ActiveAccount,
        episode: NewEpisode,
        description: String?,
        id: Int,
        silentUnderSummary: Boolean,
    ): Notification {
        val content = rowContent(episode, description, Locale.getDefault(), TimeZone.getDefault())
        return rowBuilder(
            channel = CHANNEL_ID,
            group = GROUP_KEY,
            content = content,
            open = openItem(episode.libraryItemId, episode.episodeId, id),
            play = action(NewEpisodeIntent.ACTION_PLAY, account, episode.libraryItemId, episode.episodeId, id),
            enqueue = action(NewEpisodeIntent.ACTION_ENQUEUE, account, episode.libraryItemId, episode.episodeId, id),
            silentUnderSummary = silentUnderSummary,
        )
    }

    private fun bookRow(account: ActiveAccount, book: NewBook, id: Int, silentUnderSummary: Boolean): Notification =
        rowBuilder(
            channel = BOOK_CHANNEL_ID,
            group = BOOK_GROUP_KEY,
            content = bookRowContent(book),
            open = openItem(book.libraryItemId, null, id),
            play = action(NewEpisodeIntent.ACTION_PLAY, account, book.libraryItemId, null, id),
            enqueue = action(NewEpisodeIntent.ACTION_ENQUEUE, account, book.libraryItemId, null, id),
            silentUnderSummary = silentUnderSummary,
        )

    /** One row, episode or book: the same layout, the same two buttons, the same order. */
    private fun rowBuilder(
        channel: String,
        group: String,
        content: RowContent,
        open: PendingIntent?,
        play: PendingIntent,
        enqueue: PendingIntent,
        silentUnderSummary: Boolean,
    ): Notification {
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(content.title)
            .setSubText(content.podcast.ifEmpty { null })
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.expanded))
            // The date is in the text. A second, different time in the header — the time
            // the check ran — would only invite the question which one is meant.
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_play, "Play", play)
            .addAction(android.R.drawable.ic_input_add, "Add to queue", enqueue)
            .setGroup(group)
            .setAutoCancel(true)
        // Only when a summary is posted with it: a lone row told to stay silent for a
        // summary that does not exist would never alert at all.
        if (silentUnderSummary) builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
        return builder.build()
    }

    private fun summary(episodes: List<NewEpisode>): Notification {
        val style = NotificationCompat.InboxStyle()
        episodes.take(MAX_ROWS).forEach { style.addLine("${it.podcastTitle}: ${it.episodeTitle}") }
        val more = episodes.size - MAX_ROWS
        if (more > 0) style.setSummaryText("and $more more")
        val title = if (episodes.size == 1) {
            "New episode of ${episodes.first().podcastTitle}"
        } else {
            "${episodes.size} new episodes"
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(episodes.take(MAX_ROWS).joinToString(" · ") { it.episodeTitle })
            .setStyle(style)
            .setContentIntent(tapTarget(episodes).let { openItem(it.libraryItemId, it.episodeId, SUMMARY_ID) })
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setAutoCancel(true)
            .build()
    }

    private fun bookSummary(books: List<NewBook>): Notification {
        val style = NotificationCompat.InboxStyle()
        books.take(MAX_ROWS).forEach { style.addLine(bookLine(it)) }
        val more = books.size - MAX_ROWS
        if (more > 0) style.setSummaryText("and $more more")
        val title = if (books.size == 1) "New book" else "${books.size} new books"
        // The newest book, for the reason [tapTarget] gives for episodes.
        val newest = books.first()
        return NotificationCompat.Builder(context, BOOK_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(books.take(MAX_ROWS).joinToString(" · ") { it.title })
            .setStyle(style)
            .setContentIntent(openItem(newest.libraryItemId, null, BOOK_SUMMARY_ID))
            .setGroup(BOOK_GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setAutoCancel(true)
            .build()
    }

    /**
     * Opens one episode's page, or one book's page when [episodeId] is null, with the
     * library underneath it.
     *
     * The launcher intent is still the base, so the activity starts the way it always
     * starts and the graph comes up with Home at its root; the two ids ride on it as
     * extras. That is what lets back from the episode page reach the library on a cold tap
     * instead of leaving the app.
     *
     * The request code is the notification's own id. Extras do not make two pending
     * intents different, so rows sharing one code would all open whichever episode was
     * posted last. `FLAG_UPDATE_CURRENT` then covers the summary, whose id is fixed: a
     * second batch replaces it, and its tap must replace the first one's extras as well.
     */
    private fun openItem(libraryItemId: String, episodeId: String?, requestCode: Int): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        intent.putExtra(NewEpisodeIntent.EXTRA_LIBRARY_ITEM_ID, libraryItemId)
        if (episodeId != null) intent.putExtra(NewEpisodeIntent.EXTRA_EPISODE_ID, episodeId)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * A button's broadcast, addressed to the receiver by component.
     *
     * By component rather than by package, because the receiver is not exported, and from
     * Android 14 an intent without a component does not reach a component that is not
     * exported. The action differs between the two buttons, so the pair on one row are two
     * distinct pending intents under the same request code.
     */
    private fun action(
        verb: String,
        account: ActiveAccount,
        libraryItemId: String,
        episodeId: String?,
        notificationId: Int,
    ): PendingIntent {
        val intent = Intent(verb)
            .setClassName(context.packageName, NewEpisodeIntent.ACTION_RECEIVER)
            .putExtra(NewEpisodeIntent.EXTRA_LIBRARY_ITEM_ID, libraryItemId)
            .apply { if (episodeId != null) putExtra(NewEpisodeIntent.EXTRA_EPISODE_ID, episodeId) }
            .putExtra(NewEpisodeIntent.EXTRA_SERVER_ID, account.serverId)
            .putExtra(NewEpisodeIntent.EXTRA_USER_ID, account.userId)
            .putExtra(NewEpisodeIntent.EXTRA_NOTIFICATION_ID, notificationId)
        return PendingIntent.getBroadcast(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Whether rows from an earlier batch are still in the shade beside these new ones. */
    private fun otherRowsShowing(group: String, newIds: List<Int>): Boolean =
        activeRowIds(group, summaryIdOf(group)).any { it !in newIds }

    private fun summaryIdOf(group: String) = if (group == BOOK_GROUP_KEY) BOOK_SUMMARY_ID else SUMMARY_ID

    private fun activeRowIds(group: String, summaryId: Int): List<Int> {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return emptyList()
        return runCatching { manager.activeNotifications.toList() }.getOrDefault(emptyList())
            .filter { it.notification.group == group && it.id != summaryId }
            .map { it.id }
    }

    private fun ensureChannel(id: String, name: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(id) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                id,
                name,
                // A new episode or book is news, not an interruption: it can wait for the
                // next time the phone is picked up.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    internal companion object {
        const val CHANNEL_ID = "lugu_episodes"
        const val GROUP_KEY = "lugu_new_episodes"
        const val SUMMARY_ID = 4103
        const val BOOK_CHANNEL_ID = "lugu_books"
        const val BOOK_GROUP_KEY = "lugu_new_books"
        const val BOOK_SUMMARY_ID = 4104
        const val MAX_ROWS = 5
    }
}

/** The new-episode notifier also announces new books, so there is one notifier and one receiver. */
@Module
@InstallIn(SingletonComponent::class)
abstract class NewBookAnnouncerModule {
    @Binds
    abstract fun announcer(notifier: NewEpisodeNotifier): NewBookAnnouncer
}

/**
 * One row's id, the same each time the same episode is named.
 *
 * Derived from the ids rather than counted, so a row posted again replaces itself instead
 * of appearing twice, and so the receiver can be told which row to take away. The two
 * fixed ids lugu posts under are stepped around; a clash between two episodes would only
 * replace one row with the other.
 */
internal fun notificationIdOf(episode: NewEpisode): Int =
    clearOfFixedIds("${episode.libraryItemId}/${episode.episodeId}".hashCode())

/** A book's row id. Prefixed, so a book and an episode never share an id by construction. */
internal fun notificationIdOf(book: NewBook): Int = clearOfFixedIds("book/${book.libraryItemId}".hashCode())

private fun clearOfFixedIds(id: Int): Int {
    val fixed = setOf(NewEpisodeNotifier.SUMMARY_ID, NewEpisodeNotifier.BOOK_SUMMARY_ID, NewEpisodeAction.NO_ID)
    var free = id
    while (free in fixed) free += 2
    return free
}

/** The words on one row, worked out apart from the builder so a test can read them. */
internal data class RowContent(
    val title: String,
    val podcast: String,
    /** The collapsed line: the date, then as much of the snippet as fits. */
    val text: String,
    /** The expanded body: the date on its own line, then the snippet. */
    val expanded: String,
)

/**
 * The words on one book row: the title, the author under it, and the snippet.
 *
 * The author goes in the sub-text, where an episode has its podcast, and again in front of
 * the snippet. The sub-text is small and some launchers drop it, and "by whom" is the
 * first thing somebody asks about a book.
 */
internal fun bookRowContent(book: NewBook): RowContent {
    val author = book.authorName?.takeIf { it.isNotBlank() }
    val snippet = descriptionSnippet(book.description)
    return RowContent(
        title = book.title,
        podcast = author.orEmpty(),
        text = listOfNotNull(author, snippet).joinToString(" · ").ifEmpty { "New in your library" },
        expanded = listOfNotNull(author, snippet).joinToString("\n").ifEmpty { "New in your library" },
    )
}

/** One line of the book summary. */
internal fun bookLine(book: NewBook): String {
    val author = book.authorName?.takeIf { it.isNotBlank() } ?: return book.title
    return "${book.title}, by $author"
}

internal fun rowContent(
    episode: NewEpisode,
    description: String?,
    locale: Locale,
    zone: TimeZone,
): RowContent {
    val date = publishedDate(episode.publishedAtMs, locale, zone)
    val snippet = descriptionSnippet(description)
    return RowContent(
        title = episode.episodeTitle,
        podcast = episode.podcastTitle,
        text = listOfNotNull(date, snippet).joinToString(" · ").ifEmpty { episode.podcastTitle },
        expanded = listOfNotNull(date, snippet).joinToString("\n").ifEmpty { episode.podcastTitle },
    )
}

/**
 * The publication date in the reader's own format, or null when the feed gave none.
 *
 * A date and not a time: feeds state a time of day carelessly and often in a zone nobody
 * can see, and the day is the part that answers "is this the new one".
 */
internal fun publishedDate(publishedAtMs: Long, locale: Locale, zone: TimeZone): String? {
    if (publishedAtMs <= 0L) return null
    val format = DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
    format.timeZone = zone
    return format.format(Date(publishedAtMs))
}

/**
 * The first words of a description, as plain text short enough for a notification.
 *
 * The HTML goes through [parseShowNotes], the same reading the episode page uses, so the
 * snippet and the page never disagree about what the words are. Its line breaks are then
 * folded into spaces: a notification line is one paragraph.
 *
 * A text longer than [maxChars] is cut at the last space that leaves room for the
 * ellipsis, and trailing punctuation is trimmed so it does not read "said, …". A single
 * word too long to fit is cut where it must be — but never inside a surrogate pair, which
 * would draw as a broken glyph.
 */
internal fun descriptionSnippet(description: String?, maxChars: Int = SNIPPET_MAX_CHARS): String? {
    val text = parseShowNotes(description).text.replace(WHITESPACE, " ").trim()
    if (text.isEmpty()) return null
    if (text.length <= maxChars) return text

    // Room for the ellipsis. A space at [maxChars] itself is still a candidate, because
    // the word before it may fit once its trailing comma is trimmed.
    val limit = maxChars - 1
    var space = text.lastIndexOf(' ', startIndex = maxChars)
    // A cut in the first half would throw away more than it keeps; a hard cut reads better.
    while (space > limit / 2) {
        val head = text.substring(0, space).trimEnd(*TRAILING)
        if (head.length <= limit) return "$head…"
        space = text.lastIndexOf(' ', startIndex = space - 1)
    }
    var end = limit
    if (end > 1 && Character.isHighSurrogate(text[end - 1])) end -= 1
    return text.substring(0, end).trimEnd(*TRAILING) + "…"
}

internal const val SNIPPET_MAX_CHARS = 160

private val WHITESPACE = Regex("\\s+")

private val TRAILING = charArrayOf(' ', ',', ';', ':', '.', '-', '–', '—', '(')

/**
 * Which episode a tap on the summary opens.
 *
 * It opens the **first** episode in the list, which is the newest: `PodcastRefresher`
 * returns them newest first, and the summary names them in that order, so the tap lands on
 * the episode the reader was already looking at. Each row opens its own episode.
 *
 * The alternative was to send a summary tap to Home and let the reader find them. That was
 * rejected because it throws away the one thing the notification knows exactly. Nothing
 * is lost either way — back from the episode page is Home, which is where the rest of the
 * batch is reachable from.
 */
internal fun tapTarget(episodes: List<NewEpisode>): NewEpisode = episodes.first()
