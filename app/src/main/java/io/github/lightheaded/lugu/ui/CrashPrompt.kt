package io.github.lightheaded.lugu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.sync.CrashPromptDecision
import io.github.lightheaded.lugu.core.sync.CrashReportingPrefs
import io.sentry.Sentry
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decides, once per launch, whether there is a crash worth mentioning.
 *
 * The decision is taken when the view model is created rather than on each recomposition,
 * so the banner cannot reappear because something above it recomposed, and so that
 * dismissing it is final for this run.
 */
@HiltViewModel
class CrashPromptViewModel @Inject constructor(
    private val prefs: CrashReportingPrefs,
) : ViewModel() {

    private val _pendingKey = MutableStateFlow(
        CrashPromptDecision.keyToAskAbout(
            // Null when the SDK was never initialised, which is the ordinary state with
            // crash reporting off. Treated as "did not crash" because with the reporter
            // off there is nothing to offer to send.
            crashedLastRun = Sentry.isCrashedLastRun() == true,
            lastCrashEventId = prefs.lastCrashEventId(),
            alreadyAskedKey = prefs.askedAboutKey(),
        ),
    )

    val pendingKey: StateFlow<String?> = _pendingKey.asStateFlow()

    /**
     * Records that this crash has been raised, whichever way it was answered. Declining is
     * an answer, and asking again next launch would be nagging rather than persistence.
     */
    fun acknowledge() {
        _pendingKey.value?.let(prefs::markAsked)
        _pendingKey.value = null
    }
}

/**
 * A quiet offer, after a crash, to be told what happened.
 *
 * The moment right after a crash is the only moment someone remembers what they were
 * doing, which is why this is asked at launch rather than left for them to find in
 * Settings. It is equally the moment they are least willing to be interrupted, so it is a
 * banner over the bottom of whatever is on screen and never a dialog: nothing is blocked,
 * and ignoring it is a valid answer that costs nothing.
 *
 * Drawn in a [Popup] because it is composed before the navigation host and would otherwise
 * be painted underneath it. A popup is its own window, so it floats over the app without
 * the caller having to wrap anything.
 */
@Composable
fun CrashPrompt(
    onOpenFeedback: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CrashPromptViewModel = hiltViewModel(),
) {
    val pendingKey by viewModel.pendingKey.collectAsStateWithLifecycle()
    if (pendingKey == null) return

    Popup(
        alignment = Alignment.BottomCenter,
        // Not focusable, so it never takes the back button or the keyboard from the app
        // behind it.
        properties = PopupProperties(focusable = false),
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "lugu crashed last time. Want me to look into it?",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = viewModel::acknowledge) { Text("Not now") }
                    TextButton(
                        onClick = {
                            viewModel.acknowledge()
                            onOpenFeedback()
                        },
                    ) {
                        Text("Tell me what happened")
                    }
                }
            }
        }
    }
}
