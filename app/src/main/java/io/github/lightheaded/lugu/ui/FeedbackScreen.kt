package io.github.lightheaded.lugu.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.BuildConfig
import io.github.lightheaded.lugu.core.sync.CrashReportingPrefs
import io.github.lightheaded.lugu.core.sync.PlaybackDiary
import io.github.lightheaded.lugu.core.ui.Status
import io.github.lightheaded.lugu.core.ui.StatusStrip
import io.github.lightheaded.lugu.playback.NowPlaying
import io.github.lightheaded.lugu.playback.PlaybackConnection
import io.github.lightheaded.lugu.playback.PlayerUiState
import io.sentry.Sentry
import io.sentry.protocol.Feedback
import io.sentry.protocol.SentryId
import javax.inject.Inject
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the person has typed and chosen, kept apart from what the app knows. */
private data class FeedbackForm(
    val comment: String = "",
    val attachPlaybackRecord: Boolean = true,
    val sending: Boolean = false,
    val sent: Boolean = false,
    val error: String? = null,
)

/** The screen's state, including the exact text that pressing Send would deliver. */
data class FeedbackUiState(
    val comment: String = "",
    val attachPlaybackRecord: Boolean = true,
    val crashReportingEnabled: Boolean = false,
    val refersToCrash: Boolean = false,
    val payload: String = "",
    val sending: Boolean = false,
    val sent: Boolean = false,
    val error: String? = null,
) {
    val canSend: Boolean get() = comment.isNotBlank() && !sending && !sent
}

/**
 * Assembles a piece of feedback and sends it.
 *
 * The crash it refers to comes from [CrashReportingPrefs] rather than from a navigation
 * argument, so the two entry points — Settings and the post-crash prompt — are the same
 * screen with the same code path. A screen that behaves differently depending on how it
 * was reached is a screen with two sets of bugs.
 */
@HiltViewModel
class FeedbackViewModel @Inject constructor(
    private val prefs: CrashReportingPrefs,
    private val diary: PlaybackDiary,
    private val playback: PlaybackConnection,
) : ViewModel() {

    /**
     * Read once, on creation. The id is cleared after a successful send, and the payload
     * shown to the user must not change underneath them while that happens.
     */
    private val crashEventId: String? = prefs.lastCrashEventId()

    private val form = MutableStateFlow(FeedbackForm())

    val state: StateFlow<FeedbackUiState> = combine(
        form,
        prefs.enabled,
        playback.state,
        playback.nowPlaying,
        diary.entries,
    ) { current, reportingEnabled, player, nowPlaying, _ ->
        FeedbackUiState(
            comment = current.comment,
            attachPlaybackRecord = current.attachPlaybackRecord,
            crashReportingEnabled = reportingEnabled,
            refersToCrash = crashEventId != null,
            payload = compose(current, player, nowPlaying),
            sending = current.sending,
            sent = current.sent,
            error = current.error,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FeedbackUiState())

    fun setComment(value: String) = form.update { it.copy(comment = value, error = null) }

    fun setAttachPlaybackRecord(value: Boolean) =
        form.update { it.copy(attachPlaybackRecord = value) }

    /** Puts away a send failure that has been read. The typed comment is kept. */
    fun dismissError() = form.update { it.copy(error = null) }

    /** Send with crash reporting already on, which is the only transport there is. */
    fun send() {
        viewModelScope.launch { deliver() }
    }

    /**
     * Turns crash reporting on, then sends.
     *
     * The alternative considered and rejected was a "send just this once" that starts the
     * SDK, sends, and closes it again. It sounds like the more privacy-respecting option
     * and is in fact the worse one: it would create a path by which data leaves the device
     * while the setting on the Diagnostics screen reads "off", so the one control someone
     * can check would no longer describe the app's behaviour. It would also leave the
     * reporter live for the seconds around the send, during which an unrelated crash would
     * be captured and sent — consent for one message, used for another.
     *
     * Turning it on is the honest version: the button says so, it is visible in Settings
     * afterwards, and it can be turned off again in one tap.
     */
    fun enableReportingAndSend() {
        viewModelScope.launch {
            prefs.setEnabled(true)
            // The Application observes the flag and initialises the SDK; a send issued
            // before that lands on a no-op client and is lost without saying so.
            withTimeoutOrNull(SDK_START_TIMEOUT_MS) {
                while (!Sentry.isEnabled()) delay(SDK_POLL_MS)
            }
            deliver()
        }
    }

    private suspend fun deliver() {
        val current = form.value
        if (current.comment.isBlank() || current.sending || current.sent) return

        if (!Sentry.isEnabled()) {
            // The usual cause is a build with no ingest key at all, which is the normal
            // state of a local build. Saying so beats a Send button that appears to work.
            form.update {
                it.copy(
                    sending = false,
                    error = "This build has no crash reporting configured, so there is " +
                        "nowhere for feedback to go.",
                )
            }
            return
        }

        form.update { it.copy(sending = true, error = null) }
        val payload = compose(current, playback.state.value, playback.nowPlaying.value)

        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                Sentry.feedback().capture(
                    // The setter rather than `associatedEventId = ...`: Sentry's getter
                    // and setter disagree about nullability, so Kotlin exposes the pair
                    // as a read-only synthetic property and the assignment will not
                    // compile.
                    Feedback(payload).apply {
                        crashEventId?.let { id -> setAssociatedEventId(SentryId(id)) }
                    },
                )
            }
        }

        outcome.fold(
            onSuccess = {
                // The crash has now been described, so the next launch must not ask again.
                prefs.clearLastCrash()
                form.update { it.copy(sending = false, sent = true) }
            },
            onFailure = { failure ->
                form.update {
                    it.copy(
                        sending = false,
                        error = "Could not send that: ${failure.message ?: "no reason given"}",
                    )
                }
            },
        )
    }

    private fun compose(
        current: FeedbackForm,
        player: PlayerUiState,
        nowPlaying: NowPlaying?,
    ): String = FeedbackReport.compose(
        comment = current.comment,
        context = FeedbackContext(
            appVersion = BuildConfig.VERSION_NAME,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            playbackActive = player.isPlaying || player.isBuffering,
            playerState = describe(player, nowPlaying),
            crashEventId = crashEventId,
        ),
        playbackRecord = if (current.attachPlaybackRecord) {
            FeedbackReport.tailOf(diary.asText())
        } else {
            null
        },
    )

    /**
     * The player in one line. The error text is included because it is usually the whole
     * answer, and it is safe to include because the payload passes through redaction
     * before it goes — a player error routinely carries the URL it failed to load.
     */
    private fun describe(player: PlayerUiState, nowPlaying: NowPlaying?): String = buildString {
        append(
            when {
                nowPlaying == null -> "nothing loaded"
                player.isPlaying -> "playing"
                player.isBuffering -> "buffering"
                else -> "paused"
            },
        )
        if (nowPlaying != null) {
            append(", at ${clock(player.positionSec)} of ${clock(player.durationSec)}")
            append(", speed ${player.speed}x")
        }
        player.error?.let { append(", last error: $it") }
    }

    private fun clock(seconds: Double): String {
        val total = seconds.coerceAtLeast(0.0).roundToLong()
        return "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }

    private companion object {
        const val SDK_START_TIMEOUT_MS = 3_000L
        const val SDK_POLL_MS = 50L
    }
}

/**
 * Say what went wrong, and see exactly what goes with it.
 *
 * Reached from Settings and from the post-crash prompt, with no difference between the
 * two. The expandable disclosure above the Send button is the point of the screen rather
 * than a courtesy: lugu claims to collect nothing without being asked, and the only way
 * that claim is worth anything is if the person pressing Send can read the message first.
 * What the section shows is the literal string that is sent, not a description of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FeedbackViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showingPayload by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Send feedback") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        // A Box, so that a send failure is drawn over the top of the form instead of
        // added to it. As a line above the button it pushed Send out from under the thumb
        // that had just pressed it, which invites a second press of a button that has
        // moved somewhere else.
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.sent) {
                    Text(
                        text = "Sent. Thank you — that is genuinely more useful than a stack " +
                            "trace on its own.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onBack) { Text("Done") }
                    return@Column
                }

                if (state.refersToCrash) {
                    Text(
                        text = "lugu crashed the last time it ran. What you write here will be " +
                            "attached to that crash, so the two arrive together.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                OutlinedTextField(
                    value = state.comment,
                    onValueChange = viewModel::setComment,
                    label = { Text("What happened?") },
                    placeholder = { Text("What you were doing, and what it did instead") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Attach the playback record", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "The most useful thing here, and the most detailed. It is " +
                                "shown in full below before anything is sent.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.attachPlaybackRecord,
                        onCheckedChange = viewModel::setAttachPlaybackRecord,
                    )
                }

                PayloadDisclosure(
                    payload = state.payload,
                    expanded = showingPayload,
                    onToggle = { showingPayload = !showingPayload },
                )

                if (state.crashReportingEnabled) {
                    Button(
                        onClick = viewModel::send,
                        enabled = state.canSend,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.sending) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Text("Send")
                        }
                    }
                } else {
                    ReportingOffNotice(
                        canSend = state.canSend,
                        sending = state.sending,
                        onEnableAndSend = viewModel::enableReportingAndSend,
                    )
                }
            }

            // The failure is a reply to the Send that was just pressed, so it goes
            // where every other outcome in lugu goes: over the top of the screen,
            // under the top bar, announced politely and moving nothing.
            StatusStrip(
                status = state.error?.let { Status.Problem(it) },
                onDismiss = viewModel::dismissError,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = padding.calculateTopPadding()),
            )
        }
    }
}

/**
 * The literal payload, behind one tap.
 *
 * Collapsed by default because most people will not read it, and open to anyone who
 * wants to — the value is in it being available and complete, not in it being unavoidable.
 * Selectable, so it can be copied and checked elsewhere.
 */
@Composable
private fun PayloadDisclosure(
    payload: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Exactly what gets sent",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Hide" else "Show",
                )
            }
            if (expanded) {
                SelectionContainer {
                    Text(
                        text = payload,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    )
                }
            }
        }
    }
}

/**
 * What to do when there is no transport.
 *
 * Feedback goes out over the crash reporter, so with the reporter off there is nowhere
 * for it to go. Dropping it silently would be dishonest and sending it anyway would break
 * the promise the setting exists to keep, so the screen says what is needed and offers it.
 */
@Composable
private fun ReportingOffNotice(
    canSend: Boolean,
    sending: Boolean,
    onEnableAndSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Sending needs crash reporting switched on — it is the only way lugu " +
                "has of getting anything off the phone, and it is off by default. Turning " +
                "it on sends this message and nothing else until something crashes; you " +
                "can turn it off again under Settings → Diagnostics.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = onEnableAndSend,
            enabled = canSend,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (sending) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Text("Turn on crash reporting and send")
            }
        }
    }
}
