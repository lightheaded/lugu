package io.github.lightheaded.lugu

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.lightheaded.lugu.core.download.NewEpisodeNotifier
import io.github.lightheaded.lugu.core.sync.NewEpisode
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * That a new-episode notification still knows which episode it is about when it is tapped.
 *
 * The whole path is walked rather than each half asserted on its own, because the halves
 * are in two modules and the thing that breaks is the join: `NewEpisodeNotifier` writes the
 * two ids into the intent, and `MainActivity` reads them back out. A test of either alone
 * would have passed on the old code, which built the tap from the bare launcher intent and
 * carried nothing at all.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [34],
    // A plain Application rather than lugu's own, exactly as the screenshot tests do:
    // starting the real one builds the whole Hilt graph, which reaches the keystore that
    // no JVM has. Nothing here needs the graph — a notification is built from a context.
    application = Application::class,
)
class NewEpisodeTapTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        // From API 33 a refused permission means no notification at all, and this test is
        // about what the notification carries rather than about the refusal.
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `one new episode opens that episode`() {
        val target = episodeTargetOf(
            tapIntentFor(
                listOf(
                    NewEpisode(
                        libraryItemId = "li_pod",
                        episodeId = "ep_14",
                        podcastTitle = "Coastal Signal",
                        episodeTitle = "A quiet hour on 500 kHz",
                        publishedAtMs = 1_710_000_000_000L,
                    ),
                ),
            ),
        )

        assertThat(target).isEqualTo(EpisodeTarget("li_pod", "ep_14"))
    }

    @Test
    fun `a batch opens the newest, which is the one its text names first`() {
        val target = episodeTargetOf(
            tapIntentFor(
                listOf(
                    newest,
                    NewEpisode(
                        libraryItemId = "li_pod_2",
                        episodeId = "ep_older",
                        podcastTitle = "Harbour Notes",
                        episodeTitle = "Ferry timetables as numbers stations",
                        publishedAtMs = 1_709_000_000_000L,
                    ),
                ),
            ),
        )

        assertThat(target).isEqualTo(EpisodeTarget("li_pod", "ep_newest"))
    }

    @Test
    fun `a second batch replaces what the first one would have opened`() {
        tapIntentFor(listOf(newest))

        val second = NewEpisode(
            libraryItemId = "li_pod_2",
            episodeId = "ep_after",
            podcastTitle = "Harbour Notes",
            episodeTitle = "A map of small harbours",
            publishedAtMs = 1_711_000_000_000L,
        )

        assertThat(episodeTargetOf(tapIntentFor(listOf(second))))
            .isEqualTo(EpisodeTarget("li_pod_2", "ep_after"))
    }

    @Test
    fun `an ordinary launch asks for no episode at all`() {
        assertThat(episodeTargetOf(Intent(Intent.ACTION_MAIN))).isNull()
        assertThat(episodeTargetOf(null)).isNull()
    }

    private val newest = NewEpisode(
        libraryItemId = "li_pod",
        episodeId = "ep_newest",
        podcastTitle = "Coastal Signal",
        episodeTitle = "The 40-metre band, and who is still on it",
        publishedAtMs = 1_710_000_000_000L,
    )

    /** Posts the notification these episodes would produce, and returns what a tap sends. */
    private fun tapIntentFor(episodes: List<NewEpisode>): Intent {
        NewEpisodeNotifier(application).notify(episodes)
        val manager = application.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(manager).allNotifications.last()
        return shadowOf(posted.contentIntent).savedIntent
    }
}
