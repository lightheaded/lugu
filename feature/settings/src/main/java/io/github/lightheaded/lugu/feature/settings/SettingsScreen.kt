package io.github.lightheaded.lugu.feature.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lightheaded.lugu.core.model.MediaType
import io.github.lightheaded.lugu.core.model.PodcastTrim
import io.github.lightheaded.lugu.core.model.formatSpeed
import io.github.lightheaded.lugu.core.sync.AudioSettings
import io.github.lightheaded.lugu.core.sync.DownloadSettings
import io.github.lightheaded.lugu.core.sync.HeadsetAction
import io.github.lightheaded.lugu.core.sync.NotificationPersistence
import io.github.lightheaded.lugu.core.sync.PlayerSettings
import io.github.lightheaded.lugu.core.sync.ShelfKind
import io.github.lightheaded.lugu.core.sync.SleepSettings
import io.github.lightheaded.lugu.core.sync.StartTab
import io.github.lightheaded.lugu.core.sync.SpeedSettings
import io.github.lightheaded.lugu.core.sync.StreamSettings
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
    onOpenLicenses: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPlaybackRecord: () -> Unit = {},
    onOpenFeedback: () -> Unit = {},
    onOpenConnection: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val entries = settingEntries(
        state = state,
        viewModel = viewModel,
        onSignedOut = onSignedOut,
        onOpenLicenses = onOpenLicenses,
        onOpenPlaybackRecord = onOpenPlaybackRecord,
        onOpenFeedback = onOpenFeedback,
        onOpenConnection = onOpenConnection,
    )
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
    onOpenLicenses: () -> Unit,
    onOpenPlaybackRecord: () -> Unit,
    onOpenFeedback: () -> Unit,
    onOpenConnection: () -> Unit,
): List<SettingEntry> {
    val settings = state.settings
    val downloads = state.downloads
    val queue = state.queue
    val library = state.library

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
                keywords = "notification lock screen controls headphones bluetooth chapter show " +
                    "hide order arrange icons position",
            ) {
                OrderedButtonPicker(
                    title = "In the notification",
                    subtitle = "Tap in the order you want them. Space is tight here; fewer is " +
                        "usually better.",
                    selected = settings.notificationButtons,
                    onToggle = viewModel::toggleNotificationButton,
                )
            },
        )
        add(
            SettingEntry(
                id = "notification-persistence",
                category = "Buttons",
                title = "How long the notification stays",
                keywords = "notification persistent disappears vanishes resume ready lock " +
                    "screen paused shade sticky always",
            ) {
                ChoiceRow(
                    title = "How long the notification stays",
                    subtitle = "\"Always ready\" also loads the last thing you played when " +
                        "lugu opens, so a headset button works straight away. It never " +
                        "starts playing on its own.",
                    options = NotificationPersistence.entries,
                    selected = settings.notification,
                    format = { it.label },
                    onSelect = viewModel::setNotificationPersistence,
                )
            },
        )

        add(
            SettingEntry(
                id = "headset-next",
                category = "Headset buttons",
                title = "What the next button does",
                keywords = "headphones headset bluetooth remote watch next forward button press " +
                    "avrcp car controls",
            ) {
                ChoiceRow(
                    title = "What the next button does",
                    subtitle = "A book has no tracks, so \"next\" has to mean something chosen",
                    options = HeadsetAction.entries,
                    selected = settings.headset.nextAction,
                    format = { it.label },
                    onSelect = viewModel::setHeadsetNextAction,
                )
            },
        )
        add(
            SettingEntry(
                id = "headset-previous",
                category = "Headset buttons",
                title = "What the previous button does",
                keywords = "headphones headset bluetooth remote watch previous back button press " +
                    "avrcp car controls rewind",
            ) {
                ChoiceRow(
                    title = "What the previous button does",
                    subtitle = "Android's own default here seeks to zero on a single-file book, " +
                        "which is how a forty-hour book loses its place to one press",
                    options = HeadsetAction.entries,
                    selected = settings.headset.previousAction,
                    format = { it.label },
                    onSelect = viewModel::setHeadsetPreviousAction,
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
                id = "skip-silence",
                category = "Sound",
                title = "Skip silence",
                keywords = "silence gaps pauses trim shorten faster podcast dead air",
            ) {
                SwitchRow(
                    title = "Skip silence",
                    subtitle = "Shortens long gaps. Saves real time on a badly mastered " +
                        "recording, and clips pauses a narrator meant to be there.",
                    checked = settings.audio.skipSilence,
                    onChange = viewModel::setSkipSilence,
                )
            },
        )
        add(
            SettingEntry(
                id = "volume-boost",
                category = "Sound",
                title = "Volume boost",
                keywords = "volume loud quiet gain boost car bluetooth headphones louder amplify",
            ) {
                ChoiceRow(
                    title = "Volume boost",
                    subtitle = "For quiet recordings. Past a point this is distortion rather " +
                        "than volume, so the range is deliberately short.",
                    options = AudioSettings.BOOST_CHOICES_DB,
                    selected = settings.audio.volumeBoostDb,
                    format = { if (it == 0) "Off" else "+${it} dB" },
                    onSelect = viewModel::setVolumeBoostDb,
                )
            },
        )

        add(
            SettingEntry(
                id = "duck-on-interruption",
                category = "Sound",
                title = "Lower the volume for interruptions",
                keywords = "duck ducking interruption notification navigation satnav lower quieter pause",
            ) {
                SwitchRow(
                    title = "Lower the volume for interruptions",
                    subtitle = "A navigation prompt lowers the book rather than cutting it. " +
                        "Off, anything short pauses instead.",
                    checked = settings.audio.duckOnInterruption,
                    onChange = viewModel::setDuckOnInterruption,
                )
            },
        )

        add(
            SettingEntry(
                id = "trim-intro",
                category = "Podcast trimming",
                title = "Skip the intro",
                keywords = "podcast intro sting theme tune opening skip trim seconds start jingle",
            ) {
                ChoiceRow(
                    title = "Skip the intro",
                    // The one line that stops this reading as broken. A listener who sets
                    // 15s here, then hears a show's full intro, has to be able to work out
                    // why without filing a bug about it.
                    subtitle = "Where a podcast starts from. Each show can be set differently " +
                        "from its own page.",
                    options = PodcastTrim.TRIM_CHOICES_SEC,
                    selected = settings.skip.defaultTrim.introSec,
                    format = { if (it == 0) "Off" else "${it}s" },
                    onSelect = viewModel::setDefaultTrimIntro,
                )
            },
        )
        add(
            SettingEntry(
                id = "trim-outro",
                category = "Podcast trimming",
                title = "Skip the outro",
                keywords = "podcast outro credits ending closing skip trim seconds end signoff",
            ) {
                ChoiceRow(
                    title = "Skip the outro",
                    subtitle = null,
                    options = PodcastTrim.TRIM_CHOICES_SEC,
                    selected = settings.skip.defaultTrim.outroSec,
                    format = { if (it == 0) "Off" else "${it}s" },
                    onSelect = viewModel::setDefaultTrimOutro,
                )
            },
        )
        add(
            SettingEntry(
                id = "trim-adverts",
                category = "Podcast trimming",
                title = "Skip marked adverts",
                keywords = "podcast advert ads advertising sponsor commercial promo skip chapters marked",
            ) {
                SwitchRow(
                    title = "Skip marked adverts",
                    subtitle = "Only where the episode says where they are, with a chapter " +
                        "named as advertising. An unmarked advert is not found.",
                    checked = settings.skip.defaultTrim.skipMarkedAdverts,
                    onChange = viewModel::setDefaultTrimAdverts,
                )
            },
        )
        add(
            SettingEntry(
                id = "announce-skips",
                category = "Podcast trimming",
                title = "Say when something was skipped",
                keywords = "podcast skip notice announce undo silent tell notification trim advert",
            ) {
                SwitchRow(
                    title = "Say when something was skipped",
                    subtitle = "A silent skip and a lost minute of audio look the same. The " +
                        "notice carries an Undo.",
                    checked = settings.skip.announceSkips,
                    onChange = viewModel::setAnnounceSkips,
                )
            },
        )

        add(
            SettingEntry(
                id = "sleep-survives-pause",
                category = "Sleep timer",
                title = "Keep the timer through a pause",
                keywords = "sleep timer pause cancel keep armed resume interruption",
            ) {
                SwitchRow(
                    title = "Keep the timer through a pause",
                    subtitle = "A pause is usually an interruption, not a decision to stay awake",
                    checked = settings.sleep.survivesPause,
                    onChange = viewModel::setSleepSurvivesPause,
                )
            },
        )
        add(
            SettingEntry(
                id = "sleep-fade",
                category = "Sleep timer",
                title = "Fade out before stopping",
                keywords = "sleep timer fade volume gentle wake stop night bed",
            ) {
                ChoiceRow(
                    title = "Fade out before stopping",
                    subtitle = "Stopping dead wakes people up, which rather defeats the point",
                    options = SleepSettings.FADE_CHOICES,
                    selected = settings.sleep.fadeSeconds,
                    format = { if (it == 0) "No fade" else "${it}s" },
                    onSelect = viewModel::setSleepFadeSeconds,
                )
            },
        )
        add(
            SettingEntry(
                id = "sleep-rewind",
                category = "Sleep timer",
                title = "Rewind when you come back",
                keywords = "sleep timer rewind back missed asleep resume night",
            ) {
                ChoiceRow(
                    title = "Rewind when you come back",
                    subtitle = "Whatever played in the last minutes before sleep was not " +
                        "really heard",
                    options = SleepSettings.REWIND_CHOICES,
                    selected = settings.sleep.rewindOnWakeSec,
                    format = { if (it == 0) "Off" else "${it}s" },
                    onSelect = viewModel::setRewindOnWakeSec,
                )
            },
        )
        add(
            SettingEntry(
                id = "sleep-shake",
                category = "Sleep timer",
                title = "Shake to add more time",
                keywords = "sleep timer shake extend accelerometer motion dark night more",
            ) {
                SwitchRow(
                    title = "Shake to add more time",
                    subtitle = "Buys more time without finding the screen in the dark",
                    checked = settings.sleep.shakeToExtend,
                    onChange = viewModel::setShakeToExtend,
                )
            },
        )
        if (settings.sleep.shakeToExtend) {
            add(
                SettingEntry(
                    id = "sleep-shake-sensitivity",
                    category = "Sleep timer",
                    title = "How hard to shake",
                    keywords = "sleep timer shake sensitivity threshold accidental",
                ) {
                    ChoiceRow(
                        title = "How hard to shake",
                        subtitle = "Too sensitive and rolling over resets the timer",
                        options = SleepSettings.SENSITIVITY_CHOICES,
                        selected = settings.sleep.shakeSensitivity,
                        format = { level -> listOf("A firm shake", "Normal", "A nudge")[level - 1] },
                        onSelect = viewModel::setShakeSensitivity,
                    )
                },
            )
        }
        add(
            SettingEntry(
                id = "sleep-extend",
                category = "Sleep timer",
                title = "How much time a shake adds",
                keywords = "sleep timer extend minutes shake more time",
            ) {
                ChoiceRow(
                    title = "How much time a shake adds",
                    subtitle = null,
                    options = SleepSettings.EXTEND_CHOICES,
                    selected = settings.sleep.extendMinutes,
                    format = { "$it min" },
                    onSelect = viewModel::setSleepExtendMinutes,
                )
            },
        )

        add(
            SettingEntry(
                id = "route-pause",
                category = "Headphones and car",
                title = "Pause when headphones disconnect",
                keywords = "bluetooth headphones unplug disconnect pause car noisy",
            ) {
                SwitchRow(
                    title = "Pause when headphones disconnect",
                    subtitle = "Otherwise a book carries on playing to an empty room",
                    checked = settings.route.pauseOnDisconnect,
                    onChange = viewModel::setPauseOnDisconnect,
                )
            },
        )
        add(
            SettingEntry(
                id = "route-resume-headphones",
                category = "Headphones and car",
                title = "Resume when headphones reconnect",
                keywords = "bluetooth headphones reconnect resume continue automatic",
            ) {
                SwitchRow(
                    title = "Resume when headphones reconnect",
                    subtitle = "Off by default: reconnecting headphones does not always mean " +
                        "carry on right now",
                    checked = settings.route.resumeOnHeadphones,
                    onChange = viewModel::setResumeOnHeadphones,
                )
            },
        )
        add(
            SettingEntry(
                id = "route-resume-car",
                category = "Headphones and car",
                title = "Resume when the car connects",
                keywords = "bluetooth car resume drive automatic engine android auto",
            ) {
                SwitchRow(
                    title = "Resume when the car connects",
                    subtitle = "A car connecting usually means the engine just started",
                    checked = settings.route.resumeInCar,
                    onChange = viewModel::setResumeInCar,
                )
            },
        )

        add(
            SettingEntry(
                id = "library-media-types",
                category = "Library",
                title = "What to show",
                keywords = "hide podcasts books media type tabs disable remove show library",
            ) {
                MediaTypePicker(
                    hidden = library.hiddenMediaTypes,
                    onToggle = { type, hidden -> viewModel.setMediaTypeHidden(type, hidden) },
                )
            },
        )
        add(
            SettingEntry(
                id = "library-start-tab",
                category = "Library",
                title = "Which tab opens first",
                keywords = "start home library default tab open launch first screen",
            ) {
                ChoiceRow(
                    title = "Which tab opens first",
                    subtitle = "Home is the shelves and one-tap resume; Library is the whole grid",
                    options = StartTab.entries,
                    selected = library.startTab,
                    format = { it.label },
                    onSelect = viewModel::setStartTab,
                )
            },
        )
        add(
            SettingEntry(
                id = "library-shelves",
                category = "Library",
                title = "Shelves on Home",
                keywords = "shelves home order reorder hide show continue downloaded series " +
                    "arrange move rows",
            ) {
                ShelfEditor(
                    order = library.arrangeShelves(ShelfKind.entries) { it.name },
                    hidden = library.hiddenShelves,
                    onToggle = { kind, hidden -> viewModel.setShelfHidden(kind.name, hidden) },
                    onMove = { kind, up -> viewModel.moveShelf(kind.name, up) },
                )
            },
        )
        add(
            SettingEntry(
                id = "library-shelves-scope",
                category = "Library",
                title = "Shelves follow the library picker",
                keywords = "shelves home library filter scope continue listening podcasts mixed",
            ) {
                SwitchRow(
                    title = "Shelves follow the library picker",
                    subtitle = "On, the shelves show only the library you picked. Off, they " +
                        "span everything you are part-way through.",
                    checked = library.shelvesFollowLibrary,
                    onChange = viewModel::setShelvesFollowLibrary,
                )
            },
        )

        add(
            SettingEntry(
                id = "stream-buffer",
                category = "Streaming",
                title = "Read ahead while streaming",
                keywords = "stream buffer ahead tunnel lift dropout signal network data lag stall " +
                    "underground offline gap",
            ) {
                ChoiceRow(
                    title = "Read ahead while streaming",
                    subtitle = "How long a gap in the signal a book can play through. Spoken " +
                        "word is small, so minutes cost a couple of megabytes.",
                    options = StreamSettings.BUFFER_CHOICES_MIN,
                    selected = settings.stream.bufferAheadMinutes,
                    format = { "$it min" },
                    onSelect = viewModel::setBufferAheadMinutes,
                )
            },
        )
        add(
            SettingEntry(
                id = "stream-retain",
                category = "Streaming",
                title = "Keep what you have streamed",
                keywords = "stream cache keep disk storage retain replay reuse space megabytes " +
                    "temporary scratch offline",
            ) {
                ChoiceRow(
                    title = "Keep what you have streamed",
                    // Said plainly because the two look identical on disk and are not the
                    // same promise: a download was asked for and is kept until it is
                    // deleted, this is scratch space that disappears on its own.
                    subtitle = "Disposable, and never a download. The oldest goes first when " +
                        "this fills, and nothing you downloaded is deleted to make room.",
                    options = StreamSettings.RETAIN_CHOICES_MB,
                    selected = settings.stream.retainStreamedMb,
                    format = ::formatRetainedAudio,
                    onSelect = viewModel::setRetainStreamedMb,
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
                title = "Delete books you have listened to",
                keywords = "auto delete cleanup finished listened complete reclaim space tidy",
            ) {
                // "Remove finished downloads" was ambiguous in the one way that matters:
                // finished *downloading* would mean deleting things the moment they
                // arrive. Say which finished is meant, and spell out the intervals.
                ChoiceRow(
                    title = "Delete books you have listened to",
                    subtitle = "Applies once you reach the end. Deletes the files only; your " +
                        "position and history are kept.",
                    options = DownloadSettings.AUTO_DELETE_CHOICES_DAYS,
                    selected = downloads.autoDeleteFinishedAfterDays,
                    format = ::formatAutoDeleteDelay,
                    onSelect = viewModel::setAutoDeleteFinishedAfterDays,
                )
            },
        )

        add(
            SettingEntry(
                id = "auto-download-queue",
                category = "Downloading ahead",
                title = "Download what is queued",
                keywords = "auto download queue ahead offline automatic prefetch",
            ) {
                SwitchRow(
                    title = "Download what is queued",
                    subtitle = "Anything in Up next is fetched on Wi-Fi, so it is ready when it plays",
                    checked = downloads.autoDownloadQueue,
                    onChange = viewModel::setAutoDownloadQueue,
                )
            },
        )
        add(
            SettingEntry(
                id = "auto-download-series",
                category = "Downloading ahead",
                title = "Keep the next in a series ready",
                keywords = "auto download series next book ahead offline automatic",
            ) {
                ChoiceRow(
                    title = "Keep the next in a series ready",
                    subtitle = "Only for series you have already started",
                    options = DownloadSettings.AUTO_DOWNLOAD_COUNT_CHOICES,
                    selected = downloads.autoDownloadNextInSeries,
                    format = { if (it == 0) "Off" else "$it" },
                    onSelect = viewModel::setAutoDownloadNextInSeries,
                )
            },
        )
        add(
            SettingEntry(
                id = "auto-download-episodes",
                category = "Downloading ahead",
                title = "Keep recent episodes ready",
                keywords = "auto download podcast episodes latest ahead offline automatic",
            ) {
                ChoiceRow(
                    title = "Keep recent episodes ready",
                    subtitle = "Only for podcasts you are listening to",
                    options = DownloadSettings.AUTO_DOWNLOAD_COUNT_CHOICES,
                    selected = downloads.autoDownloadLatestEpisodes,
                    format = { if (it == 0) "Off" else "$it" },
                    onSelect = viewModel::setAutoDownloadLatestEpisodes,
                )
            },
        )
        add(
            SettingEntry(
                id = "notify-new-episodes",
                category = "Downloading ahead",
                title = "Tell me about new episodes",
                keywords = "notification podcast new episode alert notify",
            ) {
                SwitchRow(
                    title = "Tell me about new episodes",
                    subtitle = "One quiet notification for the batch, for podcasts you are listening to",
                    checked = downloads.notifyNewEpisodes,
                    onChange = viewModel::setNotifyNewEpisodes,
                )
            },
        )

        add(
            SettingEntry(
                id = "queue-continue-series",
                category = "Up next",
                title = "Carry on with a series",
                keywords = "queue series next book autoplay continue automatic end",
            ) {
                SwitchRow(
                    title = "Carry on with a series",
                    subtitle = "When the queue is empty, follow a finished book with the next " +
                        "unstarted one in its series",
                    checked = queue.continueSeries,
                    onChange = viewModel::setContinueSeries,
                )
            },
        )
        add(
            SettingEntry(
                id = "queue-continue-podcast",
                category = "Up next",
                title = "Carry on with a podcast",
                keywords = "queue podcast next episode autoplay continue automatic end",
            ) {
                SwitchRow(
                    title = "Carry on with a podcast",
                    subtitle = "Follow a finished episode with the next one published",
                    checked = queue.continuePodcast,
                    onChange = viewModel::setContinuePodcast,
                )
            },
        )
        add(
            SettingEntry(
                id = "podcast-order",
                category = "Up next",
                title = "Which way a podcast runs",
                keywords = "podcast order oldest newest first serial chronological episodes " +
                    "autoplay sequence",
            ) {
                ChoiceRow(
                    title = "Which way a podcast runs",
                    subtitle = "Newest suits a news show, oldest a serial. Any podcast can be " +
                        "set differently from its own page.",
                    options = listOf(false, true),
                    selected = queue.podcastOldestFirst,
                    format = { oldest -> if (oldest) "Oldest first" else "Newest first" },
                    onSelect = viewModel::setPodcastOldestFirst,
                )
            },
        )
        add(
            SettingEntry(
                id = "queue-ask-first",
                category = "Up next",
                title = "Ask before starting something new",
                keywords = "queue confirm ask pause prompt autoplay stop end of book",
            ) {
                SwitchRow(
                    title = "Ask before starting something new",
                    subtitle = "Only applies to lugu's own suggestions — anything you queued " +
                        "yourself always plays",
                    checked = queue.askBeforeSuggestion,
                    onChange = viewModel::setAskBeforeSuggestion,
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
                LinkRow(
                    title = "Connection",
                    subtitle = "A second address for your own network, custom headers for a " +
                        "proxy, and a client certificate",
                    onClick = onOpenConnection,
                )
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

        add(
            SettingEntry(
                id = "crash-reporting",
                category = "Diagnostics",
                title = "Send crash reports",
                keywords = "crash reports diagnostics telemetry analytics privacy bug error sentry data",
            ) {
                SwitchRow(
                    title = "Send crash reports",
                    subtitle = "Off by default. When on, a crash sends its stack trace, " +
                        "app version and device model — no account details, and nothing " +
                        "about what you were listening to.",
                    checked = state.crashReporting,
                    onChange = viewModel::setCrashReporting,
                )
            },
        )

        add(
            SettingEntry(
                id = "playback-record",
                category = "Diagnostics",
                title = "Why playback stopped",
                keywords = "stopped stopping paused itself crash log record diagnose bug " +
                    "playback halted silent debug",
            ) {
                // The record is local and always on — crash reporting is opt-in and off by
                // default, and a diagnosis that only works for people who switched on
                // telemetry is no diagnosis at all.
                LinkRow(
                    title = "Why playback stopped",
                    subtitle = "A local record of starts, stops, errors and the app being " +
                        "killed. Never sent anywhere on its own.",
                    onClick = onOpenPlaybackRecord,
                )
            },
        )
        add(
            SettingEntry(
                id = "feedback",
                category = "Diagnostics",
                title = "Send feedback",
                keywords = "feedback bug report problem contact suggest tell issue crash",
            ) {
                LinkRow(
                    title = "Send feedback",
                    subtitle = "Say what went wrong, and see exactly what gets sent before " +
                        "it goes",
                    onClick = onOpenFeedback,
                )
            },
        )

        add(
            SettingEntry(
                id = "licenses",
                category = "About",
                title = "Open source licenses",
                keywords = "about legal attribution credits notices libraries oss apache gpl",
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenLicenses)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Text("Open source licenses", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "The libraries lugu is built on, and their licenses",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

/**
 * The notification's buttons, and the order they sit in.
 *
 * The tap order *is* the order, with the position shown on each chip. A separate reorder
 * control would be a second interaction for a list of four things, and a drag handle on a
 * chip is a gesture nobody discovers — this way the setting answers both questions with
 * one gesture, at the cost of having to remove and re-add to reshuffle.
 */
@Composable
private fun OrderedButtonPicker(
    title: String,
    subtitle: String?,
    selected: List<TransportButton>,
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
                val position = selected.indexOf(button)
                FilterChip(
                    selected = position >= 0,
                    onClick = { onToggle(button) },
                    label = {
                        val label = if (position >= 0) "${position + 1}. ${button.label}" else button.label
                        Text(label, maxLines = 1, softWrap = false)
                    },
                )
            }
        }
        if (selected.isEmpty()) {
            Text(
                "With none chosen the notification keeps play and pause only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * Which shelves appear on Home, and in what order.
 *
 * Up and down rather than drag: this list is six rows long and lives inside a scrolling
 * settings page, where a drag gesture fights the scroll it is nested in.
 */
@Composable
private fun ShelfEditor(
    order: List<ShelfKind>,
    hidden: Set<String>,
    onToggle: (ShelfKind, Boolean) -> Unit,
    onMove: (ShelfKind, Boolean) -> Unit,
) {
    // Hidden shelves are dropped by arrangeShelves, so they are listed after the visible
    // ones rather than vanishing — a switch you cannot find again is not a switch.
    val hiddenKinds = ShelfKind.entries.filter { it.name in hidden }

    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text("Shelves on Home", style = MaterialTheme.typography.bodyLarge)
        Text(
            "In the order they appear. Switch off the ones you never use.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        order.forEachIndexed { index, kind ->
            ShelfRow(
                kind = kind,
                visible = true,
                canMoveUp = index > 0,
                canMoveDown = index < order.lastIndex,
                onToggle = onToggle,
                onMove = onMove,
            )
        }
        hiddenKinds.forEach { kind ->
            ShelfRow(
                kind = kind,
                visible = false,
                canMoveUp = false,
                canMoveDown = false,
                onToggle = onToggle,
                onMove = onMove,
            )
        }
    }
}

@Composable
private fun ShelfRow(
    kind: ShelfKind,
    visible: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: (ShelfKind, Boolean) -> Unit,
    onMove: (ShelfKind, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            kind.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (visible) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onMove(kind, true) }, enabled = canMoveUp) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move ${kind.label} up")
        }
        IconButton(onClick = { onMove(kind, false) }, enabled = canMoveDown) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move ${kind.label} down")
        }
        Switch(checked = visible, onCheckedChange = { onToggle(kind, !it) })
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

/**
 * A row that opens somewhere else.
 *
 * The whole row is the target rather than a trailing chevron: a settings list is scanned
 * and tapped at arm's length, and a small hit area at the far edge is the one that gets
 * missed.
 */
@Composable
private fun LinkRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Which kinds of thing this listener keeps.
 *
 * Framed as what to *show* rather than what to hide, because that is the way round people
 * think about it — and the last one cannot be switched off, since an app showing nothing
 * is not a preference anyone means.
 */
@Composable
private fun MediaTypePicker(hidden: Set<MediaType>, onToggle: (MediaType, Boolean) -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text("What to show", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Switching one off removes it everywhere: the tabs, the shelves, search and " +
                "the car. Nothing is deleted, and switching it back on is instant.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MediaType.entries.forEach { type ->
                val shown = type !in hidden
                FilterChip(
                    selected = shown,
                    onClick = { onToggle(type, shown) },
                    label = { Text(mediaTypeLabel(type), maxLines = 1, softWrap = false) },
                )
            }
        }
    }
}

private fun mediaTypeLabel(type: MediaType): String = when (type) {
    MediaType.BOOK -> "Audiobooks"
    MediaType.PODCAST -> "Podcasts"
}

/**
 * "Keep none" rather than "0 MB", and gigabytes once there are whole ones.
 *
 * Zero is the choice most likely to be misread: a chip reading "0 MB" looks like a figure
 * that failed to load rather than a decision, and this is the one option that changes what
 * the setting does rather than how much of it there is.
 */
internal fun formatRetainedAudio(megabytes: Int): String = when {
    megabytes <= 0 -> "Keep none"
    megabytes % 1024 == 0 -> "${megabytes / 1024} GB"
    else -> "$megabytes MB"
}

/** "After a week" rather than "After 7d" — a setting should read like a sentence. */
internal fun formatAutoDeleteDelay(days: Int): String = when (days) {
    0 -> "Never"
    1 -> "After a day"
    7 -> "After a week"
    14 -> "After two weeks"
    30 -> "After a month"
    else -> "After $days days"
}
