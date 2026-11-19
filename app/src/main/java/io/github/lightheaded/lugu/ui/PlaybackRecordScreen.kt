package io.github.lightheaded.lugu.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lightheaded.lugu.core.sync.Clock
import io.github.lightheaded.lugu.core.sync.DiaryEntry
import io.github.lightheaded.lugu.core.sync.PlaybackDiary
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The record as this screen shows it: a reading at the top, the raw lines below. */
data class PlaybackRecordUiState(
    val summary: PlaybackRecordSummary = PlaybackRecordSummary(),
    val days: List<DiaryDay> = emptyList(),
) {
    val isEmpty: Boolean get() = days.isEmpty()
}

/**
 * Reads the playback diary for the screen.
 *
 * The counting and the grouping both live in [PlaybackRecord] rather than here, because
 * they are the parts that can be quietly wrong and they are worth testing without an
 * Android runtime.
 */
@HiltViewModel
class PlaybackRecordViewModel @Inject constructor(
    private val diary: PlaybackDiary,
    private val clock: Clock,
) : ViewModel() {

    /**
     * The time of the newest line a Clear hid, or null when nothing waits on Undo.
     *
     * A Clear hides the record at once and deletes it only when the Undo offer ends. The
     * diary has no way to write old lines back, so hiding is what makes Undo exact. Lines
     * recorded during the offer stay in view.
     */
    private val clearedUpTo = MutableStateFlow<Long?>(null)

    val state: StateFlow<PlaybackRecordUiState> = combine(diary.entries, clearedUpTo) { all, cutoff ->
        val entries = visible(all, cutoff)
        val now = clock.nowMs()
        PlaybackRecordUiState(
            summary = PlaybackRecord.summarise(entries, PlaybackRecord.startOfDay(now)),
            days = PlaybackRecord.read(entries, now),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaybackRecordUiState())

    /** The record as text, for the clipboard. Lines waiting to be cleared are left out. */
    fun asText(): String = visible(diary.entries.value, clearedUpTo.value)
        .joinToString("\n") { it.render() }

    /**
     * Hides the record now and returns the token for [undoClear] and [commitClear], or
     * null when there is nothing to clear. A token from an older Clear does nothing.
     */
    fun clear(): Long? {
        val newest = diary.entries.value.lastOrNull()?.atMs ?: return null
        // A second Clear during the offer: the first one is final.
        clearedUpTo.value?.let { commitClear(it) }
        clearedUpTo.value = newest
        return newest
    }

    fun undoClear(token: Long) {
        clearedUpTo.compareAndSet(token, null)
    }

    /** Deletes the record. Lines recorded during the offer go with it. */
    fun commitClear(token: Long) {
        if (clearedUpTo.value != token) return
        viewModelScope.launch {
            diary.clear()
            clearedUpTo.compareAndSet(token, null)
        }
    }

    /**
     * Leaving the screen during the offer is the same as letting it run out. The view
     * model's scope is already gone here, so the delete runs on a scope of its own.
     */
    override fun onCleared() {
        if (clearedUpTo.value != null) {
            CoroutineScope(Dispatchers.IO).launch { diary.clear() }
        }
    }

    private fun visible(all: List<DiaryEntry>, cutoff: Long?): List<DiaryEntry> =
        if (cutoff == null) all else all.filter { it.atMs > cutoff }
}

/**
 * Why playback stopped.
 *
 * The complaint this answers is "it stops sometimes and I cannot tell whether it crashed
 * or just stopped". The raw diary can answer that, but only for someone willing to read a
 * log, so the interpreted summary comes first and the lines are underneath it as evidence.
 * Nothing here is sent anywhere: the record is local and always on, which is what makes it
 * useful when crash reporting — opt-in and off by default — is not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackRecordScreen(
    onBack: () -> Unit,
    onSendFeedback: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaybackRecordViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Why playback stopped") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SummaryCard(summary = state.summary, isEmpty = state.isEmpty)
            }

            item {
                Text(
                    text = "This record stays on this phone. Nothing here is sent anywhere " +
                        "unless you send it yourself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                // A FlowRow, so that with a large font the buttons wrap instead of
                // squeezing their labels.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = {
                            copyToClipboard(context, viewModel.asText())
                            // Android 13 and later shows its own copy confirmation, so a
                            // second one would be the app talking over the system.
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                scope.launch { snackbarHostState.showSnackbar("Copied") }
                            }
                        },
                        enabled = !state.isEmpty,
                    ) {
                        Text("Copy")
                    }
                    TextButton(onClick = onSendFeedback) { Text("Send feedback") }
                    // No confirmation: the record goes at once and Undo brings it back.
                    TextButton(
                        onClick = {
                            val token = viewModel.clear() ?: return@TextButton
                            scope.launch {
                                var undone = false
                                try {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Record cleared",
                                        actionLabel = "Undo",
                                        withDismissAction = true,
                                        duration = SnackbarDuration.Long,
                                    )
                                    undone = result == SnackbarResult.ActionPerformed
                                } finally {
                                    // Also when the screen is left during the offer: that
                                    // cancels this coroutine, and the clear must still be
                                    // final, or a feedback report would carry the lines.
                                    if (undone) viewModel.undoClear(token) else viewModel.commitClear(token)
                                }
                            }
                        },
                        enabled = !state.isEmpty,
                    ) {
                        Text("Clear the record")
                    }
                }
            }

            state.days.forEach { day ->
                item(key = "day-${day.label}") {
                    Text(
                        text = day.label,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .semantics { heading() },
                    )
                }
                itemsIndexed(
                    items = day.lines,
                    key = { index, line -> "${day.label}-$index-${line.entry.atMs}" },
                ) { _, line ->
                    RecordRow(line)
                }
            }
        }
    }

}

/**
 * The interpreted headline. Shown even when there is nothing to report, because "nothing
 * has interrupted playback" is itself an answer to the question the screen is titled with.
 */
@Composable
private fun SummaryCard(
    summary: PlaybackRecordSummary,
    isEmpty: Boolean,
    modifier: Modifier = Modifier,
) {
    val sentence = summary.sentence("today")
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = when {
                    isEmpty -> "Nothing has been recorded yet."
                    sentence == null -> "Nothing has interrupted playback today."
                    else -> sentence
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (!isEmpty) {
                Text(
                    text = "Counted from the lines below. A stop that lugu chose — a pause, " +
                        "the end of a book — is not counted here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One line of the record: the time, what happened, and what it means where that differs. */
@Composable
private fun RecordRow(line: RecordLine, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = PlaybackRecord.timeOfDay(line.entry.atMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = line.entry.detail
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "${line.entry.event} — $it" }
                    ?: line.entry.event,
                style = MaterialTheme.typography.bodyMedium,
            )
            line.note?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("lugu playback record", text))
}
