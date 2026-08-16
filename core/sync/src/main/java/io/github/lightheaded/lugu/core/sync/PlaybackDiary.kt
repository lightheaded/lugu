package io.github.lightheaded.lugu.core.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One thing that happened to playback, with the time it happened. */
data class DiaryEntry(val atMs: Long, val event: String, val detail: String? = null) {
    fun render(): String {
        val stamp = TIME_FORMAT.format(java.util.Date(atMs))
        return if (detail.isNullOrBlank()) "$stamp  $event" else "$stamp  $event — $detail"
    }

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("MMM d HH:mm:ss", Locale.US)
    }
}

/**
 * Why playback stopped.
 *
 * lugu is asked to run for hours in a pocket, on a phone whose operating system is
 * actively looking for reasons to kill it. When it does stop, the difference between a
 * crash, an audio-focus loss, a network stall, the sleep timer and the system reclaiming
 * the service is invisible from the outside — every one of them looks like "it just
 * stopped". This is the record that tells them apart afterwards.
 *
 * Three properties make it useful rather than decorative:
 *
 *  - **It survives the process.** Entries are appended to a file, so a stop caused by the
 *    process being killed is exactly the case that still has evidence. An unexplained gap
 *    followed by a fresh "process started" line *is* the diagnosis.
 *  - **It is local and always on.** Crash reporting is opt-in and off by default, and
 *    that is not going to change; a diagnosis that only works for people who switched on
 *    telemetry is no diagnosis at all. Nothing here leaves the phone unless someone
 *    chooses to send it.
 *  - **It is bounded.** A ring of a few hundred lines covers a long drive and cannot grow
 *    into a problem of its own.
 *
 * Nothing recorded here identifies anything beyond what is already on the screen: an item
 * title, a position, an error code. No account, no server address.
 */
@Singleton
class PlaybackDiary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serialises file writes without making callers wait for one. */
    private val writes = Channel<DiaryEntry>(capacity = Channel.UNLIMITED)

    private val _entries = MutableStateFlow<List<DiaryEntry>>(emptyList())

    /** Newest last, so the interesting end is the bottom — the way a log reads. */
    val entries: StateFlow<List<DiaryEntry>> = _entries.asStateFlow()

    private val file: File get() = File(context.filesDir, FILE_NAME)

    init {
        scope.launch {
            _entries.value = runCatching { readTail() }.getOrDefault(emptyList())
            record(PROCESS_STARTED)
            for (entry in writes) {
                runCatching { append(entry) }
            }
        }
    }

    /**
     * Records an event. Never throws, never blocks, safe from any thread — including the
     * player's, which must not wait on a disk write to report that it stopped.
     */
    fun record(event: String, detail: String? = null) {
        val entry = DiaryEntry(clock.nowMs(), event, detail)
        _entries.update { (it + entry).takeLast(MAX_ENTRIES) }
        writes.trySend(entry)
    }

    /** The whole record as text, for a bug report or the clipboard. */
    fun asText(): String = _entries.value.joinToString("\n") { it.render() }

    suspend fun clear() {
        _entries.value = emptyList()
        runCatching { file.delete() }
    }

    private fun append(entry: DiaryEntry) {
        val line = buildString {
            append(entry.atMs)
            append(SEPARATOR)
            append(entry.event.sanitised())
            append(SEPARATOR)
            append(entry.detail.orEmpty().sanitised())
            append('\n')
        }
        file.appendText(line)
        // Rewriting only once the file is well past the cap keeps this to roughly one
        // rewrite per few hundred events rather than one per event.
        if (file.length() > MAX_BYTES) rewrite()
    }

    private fun rewrite() {
        val kept = _entries.value.takeLast(MAX_ENTRIES)
        val text = kept.joinToString("") { entry ->
            "${entry.atMs}$SEPARATOR${entry.event.sanitised()}$SEPARATOR${entry.detail.orEmpty().sanitised()}\n"
        }
        file.writeText(text)
    }

    private fun readTail(): List<DiaryEntry> {
        if (!file.exists()) return emptyList()
        return file.readLines()
            .takeLast(MAX_ENTRIES)
            .mapNotNull { line ->
                val parts = line.split(SEPARATOR)
                if (parts.size < 2) return@mapNotNull null
                val at = parts[0].toLongOrNull() ?: return@mapNotNull null
                DiaryEntry(at, parts[1], parts.getOrNull(2)?.takeIf { it.isNotBlank() })
            }
    }

    /** The separator is the only character that would corrupt a line, so it is the only one removed. */
    private fun String.sanitised(): String = replace(SEPARATOR, " ").replace('\n', ' ')

    companion object {
        /** Written on every start, so a gap in the record identifies a killed process. */
        const val PROCESS_STARTED = "process started"

        private const val FILE_NAME = "playback-diary.log"
        /** ASCII unit separator: it cannot occur in a title, an error code or a path. */
        private const val SEPARATOR = "\u001F"
        private const val MAX_ENTRIES = 400
        private const val MAX_BYTES = 256L * 1024
    }
}

/**
 * The events worth recording, named once.
 *
 * Constants rather than free strings because these are read back by a person trying to
 * tell two failures apart, and "paused" written three different ways is how a record
 * stops being searchable.
 *
 * These are the ones that describe playback itself, and they are the ones any module may
 * record. `LuguPlaybackService` names a handful more in its own companion — the headset
 * button, the audio focus policy, the notification hold, arming — because they describe
 * what that service is doing rather than what playback is doing, and each is written in
 * exactly one place. Look there as well as here when reading a record.
 */
object PlaybackEvent {
    const val PLAY_REQUESTED = "play requested"
    const val PLAYING = "playing"
    const val PAUSED = "paused"
    const val BUFFERING = "buffering"
    const val ENDED = "ended"
    const val IDLE = "idle"
    const val ERROR = "player error"
    const val SUPPRESSED = "playback suppressed"
    const val UNSUPPRESSED = "playback resumed after suppression"
    const val AUDIO_ROUTE_LOST = "audio route lost"
    const val AUDIO_ROUTE_GAINED = "audio route connected"
    const val SLEEP_TIMER_FIRED = "sleep timer stopped playback"

    /**
     * Worth recording even though nothing stopped: the opposite question — *why did the
     * timer not fire* — is asked just as often, and a shake nobody remembers giving is the
     * answer.
     */
    const val SLEEP_TIMER_EXTENDED = "sleep timer extended"
    const val SERVICE_CREATED = "playback service created"
    const val SERVICE_DESTROYED = "playback service destroyed"
    const val TASK_REMOVED = "app swiped away"
    const val CONTINUATION = "started the next item"
    const val UNEXPECTED_STOP = "stopped without being asked"
}
