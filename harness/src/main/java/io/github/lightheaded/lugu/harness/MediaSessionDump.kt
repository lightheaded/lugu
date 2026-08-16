package io.github.lightheaded.lugu.harness

import java.security.MessageDigest

/**
 * What the platform thinks lugu is playing, read from outside lugu.
 *
 * `dumpsys media_session` is the observation channel because it is the only one that
 * proves the interesting thing. A [androidx.media3.session.MediaController] would give
 * typed values, but connecting one *binds the service*, which starts lugu's process — so a
 * media button that did nothing at all would still be followed by a controller that
 * quietly brought the app back, and the test would pass having proved the opposite of what
 * it claims. Reading the system's own record touches nothing.
 *
 * It is also the channel the manual recipe in docs/qa/instrumented.md already uses, so a
 * failure here can be reproduced by hand with one adb command.
 *
 * The parsing lives in the main source set, and not beside the tests that use it, so that it
 * can be held to a fixture on the JVM. Everything else here needs a device and a server and
 * a book; this needs neither, and it is the part most likely to break quietly. A dump this
 * cannot read comes back as null, which is indistinguishable from an app that is not
 * running — the one misreading that would make every test in the module lie.
 */
internal object MediaSessionDump {

    /** What produces the text [parse] reads. Named once so the doc and the test agree. */
    const val COMMAND = "dumpsys media_session"

    /** `PlaybackState.STATE_PLAYING`. Not imported, to keep this module free of media3. */
    private const val STATE_PLAYING = 3

    private val PLAYBACK_STATE = Regex("""state=PlaybackState \{(.*?)\}""")

    /**
     * The session belonging to [pkg] in [dump], or null when there is none.
     *
     * Null is a real answer, not a failure: after the process dies the session record goes
     * with it, and "there is no session" is exactly what a killed app looks like.
     *
     * @param readAt `SystemClock.elapsedRealtime()` at the moment the dump was taken. Passed
     *   in rather than read here so that this stays a function of its input.
     */
    fun parse(dump: String, pkg: String, readAt: Long): PlaybackSnapshot? {
        val blocks = blocksFor(dump, pkg).mapNotNull { parseBlock(it, readAt) }
        // A stale record can outlive the session that made it for a moment. The live one is
        // the one that says it is playing.
        return blocks.firstOrNull { it.isPlaying } ?: blocks.firstOrNull()
    }

    /**
     * Every session record naming [pkg], as the lines that follow its `package=` line.
     *
     * `MediaSessionRecord.dump` writes `package=` before the state and the metadata, one
     * record after another, so a record is everything up to the next `package=`. Parsing it
     * this way survives the fields around them being reordered, which they have been.
     */
    private fun blocksFor(dump: String, pkg: String): List<List<String>> {
        val lines = dump.lines().map(String::trim)
        val starts = lines.indices.filter { lines[it] == "package=$pkg" }
        return starts.map { start ->
            val end = lines.withIndex()
                .firstOrNull { (i, line) -> i > start && line.startsWith("package=") }
                ?.index ?: lines.size
            lines.subList(start, end)
        }
    }

    private fun parseBlock(block: List<String>, readAt: Long): PlaybackSnapshot? {
        val state = block.firstNotNullOfOrNull { PLAYBACK_STATE.find(it) }
            ?.groupValues?.get(1)
            ?: return null

        // `state=3, position=1234, buffered position=5678, speed=1.5, updated=99999, ...`.
        // Split on the separator the platform writes rather than matching each field with
        // its own expression: `position` is a prefix of `buffered position`, and a regex
        // that forgets it picks the wrong number roughly half the time.
        val fields = state.split(", ").mapNotNull { field ->
            val name = field.substringBefore('=', missingDelimiterValue = "")
            if (name.isEmpty()) null else name to field.substringAfter('=')
        }.toMap()

        val stateCode = fields["state"]?.let(::stateCodeOf) ?: return null
        val position = fields["position"]?.toLongOrNull() ?: return null
        val speed = fields["speed"]?.toFloatOrNull() ?: return null
        val updated = fields["updated"]?.toLongOrNull() ?: 0L

        return PlaybackSnapshot(
            identity = identityOf(block),
            stateCode = stateCode,
            reportedPositionMs = position,
            reportedAt = updated,
            readAt = readAt,
            speed = speed,
        )
    }

    /**
     * The state, whichever way this Android wrote it down.
     *
     * Android 16 prints `state=PLAYING(3)`; older releases print `state=3`. Reading only the
     * bare integer had every snapshot come back unparsed and therefore null, which looks
     * exactly like lugu holding no session at all — a harness that would have reported the
     * app dead while it was playing.
     */
    private fun stateCodeOf(raw: String): Int? =
        raw.substringAfter('(', missingDelimiterValue = "").substringBefore(')').toIntOrNull()
            ?: raw.toIntOrNull()

    /**
     * A short digest of the item's title, and never the title itself.
     *
     * The whole point of the assertion is that the *same* thing came back, which a
     * comparison of two digests answers as well as a comparison of two strings. What the
     * digest additionally does is keep a real library title out of a failure message, and
     * so out of a CI log — see AGENTS.md. Nothing in this repository may name what is on
     * somebody's shelf.
     *
     * Only the first component of the platform's description is taken. It writes
     * `title, subtitle, description`, and lugu's subtitle is the chapter — which is allowed
     * to differ across a resumption that rewinds a few seconds over a chapter boundary.
     */
    private fun identityOf(block: List<String>): String {
        val description = block.firstOrNull { it.startsWith("metadata:") }
            ?.substringAfter("description=", missingDelimiterValue = "")
            ?.substringBefore(", ")
            .orEmpty()
        if (description.isEmpty() || description == "null") return "none"
        val bytes = MessageDigest.getInstance("SHA-256").digest(description.toByteArray())
        return bytes.take(4).joinToString("") { "%02x".format(it) }
    }

    /**
     * One reading of the platform's playback state.
     *
     * @param reportedPositionMs the position the session last published, which is not the
     *   position now: a session publishes on events rather than on a timer.
     * @param reportedAt `SystemClock.elapsedRealtime()` when it published, the same clock
     *   this process reads.
     */
    data class PlaybackSnapshot(
        val identity: String,
        val stateCode: Int,
        val reportedPositionMs: Long,
        val reportedAt: Long,
        val readAt: Long,
        val speed: Float,
    ) {
        val isPlaying: Boolean get() = stateCode == STATE_PLAYING

        /**
         * Where the book actually is, which is what a listener would hear.
         *
         * The published position is a stamp with a time on it, and the platform's own
         * clients extrapolate it exactly like this. Comparing raw published positions
         * instead would let a reading that is thirty seconds stale look like a resumption
         * that jumped thirty seconds forward.
         */
        val positionMs: Long
            get() = if (isPlaying && reportedAt > 0) {
                reportedPositionMs + ((readAt - reportedAt) * speed).toLong()
            } else {
                reportedPositionMs
            }
    }
}
