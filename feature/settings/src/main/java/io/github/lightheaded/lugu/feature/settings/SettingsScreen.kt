package io.github.lightheaded.lugu.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lightheaded.lugu.core.sync.DownloadSettings
import io.github.lightheaded.lugu.core.sync.PlayerSettings
import io.github.lightheaded.lugu.core.sync.SpeedSettings
import io.github.lightheaded.lugu.core.sync.TransportButton

/**
 * Settings, grouped by what the listener is trying to change rather than by which
 * subsystem implements it — and searchable, because categories stop helping somewhere
 * around the third screenful.
 *
 * Every setting is declared as an entry with its own synonyms rather than written
 * inline, so adding one automatically makes it findable. The alternative — a search
 * index maintained separately from the UI — goes stale the first time someone adds a
 * setting and forgets, and a search that silently omits a setting is worse than none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val entries = settingEntries(state, viewModel, onSignedOut)
    val visible = SettingsIndex.filter(entries, state.query)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text("Search settings") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
                    Text(
                        "Nothing matches “${state.query}”.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                // Headers come from the entries themselves, so filtering can never leave
                // a category heading with nothing under it.
                items(visible, key = { it.id }) { entry ->
                    val isFirstOfCategory = visible.first { it.category == entry.category }.id == entry.id
                    if (isFirstOfCategory) SectionHeader(entry.category)
                    entry.content()
                }
            }
        }
    }
}

/**
 * The whole settings surface, declared once.
 *
 * Kept as data rather than as a hand-written column so that search, ordering and
 * rendering all read from the same list.
 */
@Composable
private fun settingEntries(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onSignedOut: () -> Unit,
): List<SettingEntry> {
    val settings = state.settings
    val downloads = state.downloads

    return buildList {
        add(
            SettingEntry(
                id = "skip-back",
                category = "Skipping",
                title = "Skip back",
                keywords = "rewind back seconds jump replay again re-listen missed",
            ) {
                ChoiceRow(
                    title = "Skip back",
                    subtitle = "Used most often, to re-hear something missed",
                    options = PlayerSettings.SKIP_CHOICES,
                    selected = settings.skipBackSec,
                    format = { "${it}s" },
                    onSelect = viewModel::setSkipBack,
                )
            },
        )
        add(
            SettingEntry(
                id = "skip-forward",
                category = "Skipping",
                title = "Skip forward",
                keywords = "forward fast skip ahead seconds jump",
            ) {
                ChoiceRow(
                    title = "Skip forward",
                    subtitle = null,
                    options = PlayerSettings.SKIP_CHOICES,
                    selected = settings.skipForwardSec,
                    format = { "${it}s" },
                    onSelect = viewModel::setSkipForward,
                )
            },
        )

        add(
            SettingEntry(
                id = "notice-duration",
                category = "Notices",
                title = "How long a notice stays up",
                keywords = "toast snackbar popup message rewound jumped undo timeout dismiss duration",
            ) {
                ChoiceRow(
                    title = "How long a notice stays up",
                    subtitle = "Applies to the rewound and jumped notices, which carry an Undo",
                    options = PlayerSettings.NOTICE_CHOICES,
                    selected = settings.noticeSeconds,
                    format = { "${it}s" },
                    onSelect = viewModel::setNoticeSeconds,
                )
            },
        )

        add(
            SettingEntry(
                id = "buttons-player",
                category = "Buttons",
                title = "Buttons in the player",
                keywords = "controls transport chapter layout screen show hide",
            ) {
                ButtonPicker(
                    title = "In the player",
                    subtitle = "The skip pair is always shown. Choose what else appears.",
                    selected = settings.playerButtons,
                    onToggle = viewModel::togglePlayerButton,
                )
            },
        )
        add(
            SettingEntry(
                id = "buttons-notification",
                category = "Buttons",
                title = "Buttons in the notification",
                keywords = "notification lock screen controls headphones bluetooth chapter show hide",
            ) {
                ButtonPicker(
                    title = "In the notification",
                    subtitle = "Space is tight here; fewer is usually better",
                    selected = settings.notificationButtons,
                    onToggle = viewModel::toggleNotificationButton,
                )
            },
        )

        add(
            SettingEntry(
                id = "speed-default",
                category = "Speed",
                title = "Default speed",
                keywords = "tempo rate faster slower 1.5x 2x pace",
            ) {
                SpeedRow(
                    title = "Default speed",
                    speed = settings.speed.defaultSpeed,
                    onChange = viewModel::setDefaultSpeed,
                )
            },
        )
        add(
            SettingEntry(
                id = "speed-podcast-separate",
                category = "Speed",
                title = "Separate speed for podcasts",
                keywords = "podcast rate tempo different pace",
            ) {
                SwitchRow(
                    title = "Separate speed for podcasts",
                    subtitle = "Podcasts often suit a different pace to books",
                    checked = settings.speed.separatePodcastSpeed,
                    onChange = viewModel::setSeparatePodcastSpeed,
                )
            },
        )
        if (settings.speed.separatePodcastSpeed) {
            add(
                SettingEntry(
                    id = "speed-podcast-default",
                    category = "Speed",
                    title = "Default podcast speed",
                    keywords = "podcast rate tempo pace",
                ) {
                    SpeedRow(
                        title = "Default podcast speed",
                        speed = settings.speed.defaultPodcastSpeed,
                        onChange = viewModel::setDefaultPodcastSpeed,
                    )
                },
            )
        }
        add(
            SettingEntry(
                id = "speed-remember-book",
                category = "Speed",
                title = "Remember speed per book",
                keywords = "per book narrator remember sticky",
            ) {
                SwitchRow(
                    title = "Remember speed per book",
                    subtitle = null,
                    checked = settings.speed.rememberPerBook,
                    onChange = viewModel::setRememberPerBook,
                )
            },
        )
        add(
            SettingEntry(
                id = "speed-remember-podcast",
                category = "Speed",
                title = "Remember speed per podcast",
                keywords = "per podcast series episode narrator remember sticky",
            ) {
                SwitchRow(
                    title = "Remember speed per podcast",
                    subtitle = "Kept for the whole podcast, not one episode — the narrator is the same",
                    checked = settings.speed.rememberPerPodcast,
                    onChange = viewModel::setRememberPerPodcast,
                )
            },
        )
        add(
            SettingEntry(
                id = "speed-presets",
                category = "Speed",
                title = "Speed presets",
                keywords = "presets choices chips one tap 1.5x 2x custom",
            ) {
                PresetEditor(
                    presets = settings.speed.presets,
                    onAdd = viewModel::addSpeedPreset,
                    onRemove = viewModel::removeSpeedPreset,
                )
            },
        )

        add(
            SettingEntry(
                id = "download-wifi",
                category = "Downloads",
                title = "Download on Wi-Fi only",
                keywords = "wifi mobile data cellular metered offline allowance roaming",
            ) {
                SwitchRow(
                    title = "Wi-Fi only",
                    subtitle = "A book is often a gigabyte or two",
                    checked = downloads.wifiOnly,
                    onChange = viewModel::setWifiOnly,
                )
            },
        )
        add(
            SettingEntry(
                id = "download-charging",
                category = "Downloads",
                title = "Only while charging",
                keywords = "charging battery power plugged offline",
            ) {
                SwitchRow(
                    title = "Only while charging",
                    subtitle = null,
                    checked = downloads.requiresCharging,
                    onChange = viewModel::setRequiresCharging,
                )
            },
        )
        add(
            SettingEntry(
                id = "download-cap",
                category = "Downloads",
                title = "Storage cap",
                keywords = "storage space limit size gb full disk offline",
            ) {
                ChoiceRow(
                    title = "Storage cap",
                    subtitle = "Downloads stop before this; nothing is ever deleted to make room",
                    options = DownloadSettings.CAP_CHOICES_BYTES,
                    selected = downloads.storageCapBytes,
                    format = { "${it / (1024 * 1024 * 1024)} GB" },
                    onSelect = viewModel::setStorageCap,
                )
            },
        )
        add(
            SettingEntry(
                id = "download-auto-delete",
                category = "Downloads",
                title = "Remove finished downloads",
                keywords = "auto delete cleanup finished reclaim space tidy",
            ) {
                ChoiceRow(
                    title = "Remove finished downloads",
                    subtitle = "Deletes the files only; your position and history are kept",
                    options = DownloadSettings.AUTO_DELETE_CHOICES_DAYS,
                    selected = downloads.autoDeleteFinishedAfterDays,
                    format = { if (it == 0) "Never" else "After ${it}d" },
                    onSelect = viewModel::setAutoDeleteFinishedAfterDays,
                )
            },
        )

        add(
            SettingEntry(
                id = "account",
                category = "Account",
                title = "Account",
                keywords = "server sign out log out user session address",
            ) {
                state.account?.let { account ->
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                        Text(account.username, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            account.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(
                    onClick = {
                        viewModel.signOut()
                        onSignedOut()
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text("Sign out", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column {
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    subtitle: String?,
    options: List<T>,
    selected: T,
    format: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(format(option), maxLines = 1, softWrap = false) },
                )
            }
        }
    }
}

@Composable
private fun ButtonPicker(
    title: String,
    subtitle: String? = null,
    selected: Set<TransportButton>,
    onToggle: (TransportButton) -> Unit,
) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransportButton.entries.forEach { button ->
                FilterChip(
                    selected = button in selected,
                    onClick = { onToggle(button) },
                    label = { Text(button.label, maxLines = 1, softWrap = false) },
                )
            }
        }
    }
}

@Composable
private fun SpeedRow(title: String, speed: Float, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeedSettings.DEFAULT_PRESETS.forEach { option ->
                FilterChip(
                    selected = kotlin.math.abs(option - speed) < 0.01f,
                    onClick = { onChange(option) },
                    label = { Text(formatSpeed(option), maxLines = 1, softWrap = false) },
                )
            }
        }
    }
}

@Composable
private fun PresetEditor(presets: List<Float>, onAdd: (Float) -> Unit, onRemove: (Float) -> Unit) {
    val candidates = listOf(0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f, 1.8f, 2.0f, 2.5f, 3.0f)
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text("Speed presets", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Shown as one-tap options in the player",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            candidates.forEach { speed ->
                val chosen = presets.any { kotlin.math.abs(it - speed) < 0.01f }
                FilterChip(
                    selected = chosen,
                    onClick = { if (chosen) onRemove(speed) else onAdd(speed) },
                    label = { Text(formatSpeed(speed), maxLines = 1, softWrap = false) },
                )
            }
        }
    }
}

/** "2x" rather than "2.0x", which wraps onto two lines in a narrow chip. */
internal fun formatSpeed(speed: Float): String =
    if (kotlin.math.abs(speed - speed.toInt()) < 0.01f) "${speed.toInt()}x"
    else "${(speed * 100).toInt() / 100.0}x"
