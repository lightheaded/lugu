package io.github.lightheaded.lugu.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import io.github.lightheaded.lugu.core.db.DownloadState
import io.github.lightheaded.lugu.core.download.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A failed download must not change the height of its row.
 *
 * This is the claim the failure line was built on, and no other test reaches it. The words
 * come from the server, so their length is not ours to choose. A row that grows pushes every
 * row under it down, and it moves the row that a finger already travels towards.
 *
 * The states are measured rather than photographed. A picture proves what a row looks like,
 * and the promise here is arithmetic: the same height, whatever the state. A measurement
 * states that directly, and it needs no baseline on any host.
 *
 * All four states are drawn in one composition, because a compose rule takes content once.
 * Each row sits in a tagged [Box], and a box is exactly as tall as the row inside it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class DownloadRowHeightTest {

    @get:Rule
    val compose = createComposeRule()

    private fun status(state: String, error: String? = null) = DownloadStatus(
        libraryItemId = "item-1",
        episodeId = null,
        title = "The Lighthouse Wakes",
        author = "James T. R. Corven",
        state = state,
        percent = 0.42f,
        bytesDownloaded = 12_000_000L,
        bytesTotal = 30_000_000L,
        error = error,
    )

    private val longFailure =
        "The download stopped because the storage cap for this account is reached. " +
            "Delete a finished book to make room. Raise the cap in settings. " +
            "Then start the download again from the item page."

    @Before
    fun drawEveryState() {
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface {
                    Column {
                        Row("complete", status(DownloadState.COMPLETED))
                        Row("active", status(DownloadState.DOWNLOADING))
                        Row("failed-short", status(DownloadState.FAILED, "Disk full."))
                        Row("failed-long", status(DownloadState.FAILED, longFailure))
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Row(tag: String, download: DownloadStatus) {
        Box(Modifier.testTag(tag)) {
            DownloadRowView(
                download = download,
                selectionActive = false,
                isSelected = false,
                onOpen = {},
                onToggle = {},
                onRemove = {},
                onRetry = {},
                onExplain = {},
            )
        }
    }

    private fun heightOf(tag: String): Int =
        compose.onNodeWithTag(tag).fetchSemanticsNode().size.height

    @Test
    fun `a complete row and a failed row are the same height`() {
        assertEquals(heightOf("complete"), heightOf("failed-short"))
    }

    @Test
    fun `a long failure holds the row to the height of a short one`() {
        assertEquals(heightOf("failed-short"), heightOf("failed-long"))
    }

    @Test
    fun `a row in flight is the same height as a failed row`() {
        assertEquals(heightOf("active"), heightOf("failed-short"))
    }
}
