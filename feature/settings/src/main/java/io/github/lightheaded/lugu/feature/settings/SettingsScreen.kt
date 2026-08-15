package io.github.lightheaded.lugu.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import io.github.lightheaded.lugu.core.sync.PlayerSettings
import io.github.lightheaded.lugu.core.sync.SpeedSettings
import io.github.lightheaded.lugu.core.sync.TransportButton

/**
 * Settings, grouped by what the listener is trying to change rather than by which
 * subsystem implements it.
 *
 * Search is not here yet; the categories are the ordering that search will index.
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
    val settings = state.settings

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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
        ) {
            item { SectionHeader("Skipping") }
            item {
                ChoiceRow(
                    title = "Skip back",
                    subtitle = "Used most often, to re-hear something missed",
                    options = PlayerSettings.SKIP_CHOICES,
                    selected = settings.skipBackSec,
                    format = { "${it}s" },
                    onSelect = viewModel::setSkipBack,
                )
            }
            item {
                ChoiceRow(
                    title = "Skip forward",
                    subtitle = null,
                    options = PlayerSettings.SKIP_CHOICES,
                    selected = settings.skipForwardSec,
                    format = { "${it}s" },
                    onSelect = viewModel::setSkipForward,
                )
            }

            item { SectionHeader("Buttons") }
            item {
                Text(
                    "The skip pair is always shown. Choose what else appears.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            item {
                ButtonPicker(
                    title = "In the player",
                    selected = settings.playerButtons,
                    onToggle = viewModel::togglePlayerButton,
                )
            }
            item {
                ButtonPicker(
                    title = "In the notification",
                    subtitle = "Space is tight here; fewer is usually better",
                    selected = settings.notificationButtons,
                    onToggle = viewModel::toggleNotificationButton,
                )
            }

            item { SectionHeader("Speed") }
            item {
                SpeedRow(
                    title = "Default speed",
                    speed = settings.speed.defaultSpeed,
                    onChange = viewModel::setDefaultSpeed,
                )
            }
            item {
                SwitchRow(
                    title = "Separate speed for podcasts",
                    subtitle = "Podcasts often suit a different pace to books",
                    checked = settings.speed.separatePodcastSpeed,
                    onChange = viewModel::setSeparatePodcastSpeed,
                )
            }
            if (settings.speed.separatePodcastSpeed) {
                item {
                    SpeedRow(
                        title = "Default podcast speed",
                        speed = settings.speed.defaultPodcastSpeed,
                        onChange = viewModel::setDefaultPodcastSpeed,
                    )
                }
            }
            item {
                SwitchRow(
                    title = "Remember speed per book",
                    subtitle = null,
                    checked = settings.speed.rememberPerBook,
                    onChange = viewModel::setRememberPerBook,
                )
            }
            item {
                SwitchRow(
                    title = "Remember speed per podcast",
                    subtitle = "Kept for the whole podcast, not one episode — the narrator is the same",
                    checked = settings.speed.rememberPerPodcast,
                    onChange = viewModel::setRememberPerPodcast,
                )
            }
            item {
                PresetEditor(
                    presets = settings.speed.presets,
                    onAdd = viewModel::addSpeedPreset,
                    onRemove = viewModel::removeSpeedPreset,
                )
            }

            item { SectionHeader("Account") }
            item {
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
            }
        }
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
