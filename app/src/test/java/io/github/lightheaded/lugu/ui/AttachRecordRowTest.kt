package io.github.lightheaded.lugu.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The feedback screen's attach switch is one control for the whole row, the same as the
 * switch rows in Settings.
 */
@RunWith(AndroidJUnit4::class)
// A plain Application, for the reason PlaybackRecordScreenshotTest gives.
@Config(sdk = [34], application = Application::class)
class AttachRecordRowTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a tap on the row's subtitle flips the switch`() {
        compose.setContent {
            var checked by remember { mutableStateOf(true) }
            MaterialTheme { AttachRecordRow(checked = checked, onChange = { checked = it }) }
        }

        compose.onNodeWithText("Attach the playback record").assertIsOn()
        compose.onNodeWithText("shown in full below", substring = true, useUnmergedTree = true)
            .performClick()

        compose.onNodeWithText("Attach the playback record").assertIsOff()
    }

    @Test
    fun `the row is one control, not a title and a switch`() {
        compose.setContent {
            MaterialTheme { AttachRecordRow(checked = false, onChange = {}) }
        }

        compose.onNodeWithText("Attach the playback record").assert(isToggleable())
        compose.onAllNodesWithText("Attach the playback record").assertCountEquals(1)
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
    }
}
