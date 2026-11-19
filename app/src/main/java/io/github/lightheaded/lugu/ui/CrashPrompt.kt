package io.github.lightheaded.lugu.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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

    /**
     * Puts the offer away for this run without marking it asked, so the next launch offers
     * it again. For when another notice took its place before the listener answered.
     */
    fun deferToNextLaunch() {
        _pendingKey.value = null
    }
}

/**
 * A quiet offer, after a crash, to be told what happened.
 *
 * The moment right after a crash is the only moment someone remembers what they were
 * doing, which is why this is asked at launch rather than left for them to find in
 * Settings. It is equally the moment they are least willing to be interrupted, so it is a
 * snackbar and never a dialog: nothing is blocked, and ignoring it is a valid answer.
 *
 * It goes through the shell's snackbar host, which sits above the mini player and Home's
 * tab bar, so the offer does not cover the controls at the bottom as the old popup did.
 * It stays until answered. A jump notice can take its place: see [yieldCrashPrompt].
 */
@Composable
fun CrashPrompt(
    snackbarHostState: SnackbarHostState,
    onOpenFeedback: () -> Unit,
    viewModel: CrashPromptViewModel = hiltViewModel(),
) {
    val pendingKey by viewModel.pendingKey.collectAsStateWithLifecycle()
    LaunchedEffect(pendingKey) {
        if (pendingKey == null) return@LaunchedEffect
        val visuals = CrashPromptVisuals()
        val result = snackbarHostState.showSnackbar(visuals)
        when {
            result == SnackbarResult.ActionPerformed -> {
                viewModel.acknowledge()
                onOpenFeedback()
            }
            visuals.yielded -> viewModel.deferToNextLaunch()
            // Dismissed by the listener. Declining is an answer too.
            else -> viewModel.acknowledge()
        }
    }
}

/**
 * Takes the crash offer off the screen, if it shows, so that another notice can show now.
 *
 * The offer waits for an answer and the host shows one snackbar at a time, so a jump
 * notice with Undo would otherwise wait behind it and time out unseen. A yielded offer is
 * not counted as answered: the next launch offers it again.
 */
fun SnackbarHostState.yieldCrashPrompt() {
    val current = currentSnackbarData ?: return
    val visuals = current.visuals as? CrashPromptVisuals ?: return
    visuals.yielded = true
    current.dismiss()
}

// One voice: lugu names itself, and the action is the reader's own.
private class CrashPromptVisuals : SnackbarVisuals {
    override val message: String = "lugu crashed last time."
    override val actionLabel: String = "Describe what happened"
    override val withDismissAction: Boolean = true
    override val duration: SnackbarDuration = SnackbarDuration.Indefinite

    /** Set when another notice took the offer's place. */
    var yielded: Boolean = false
}
