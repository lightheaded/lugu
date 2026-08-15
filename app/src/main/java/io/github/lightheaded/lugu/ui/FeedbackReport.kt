package io.github.lightheaded.lugu.ui

import io.github.lightheaded.lugu.Redaction

/**
 * Everything lugu knows about the run, other than what the person types.
 *
 * Deliberately small, and deliberately without a title: the playback record already
 * carries what was playing where that matters, and it is the part the user can switch
 * off. Putting the book title in the always-attached header would make the one thing
 * nobody can decline the one thing that says what they were listening to.
 */
data class FeedbackContext(
    val appVersion: String,
    val deviceModel: String,
    val androidVersion: String,
    val playbackActive: Boolean,
    val playerState: String,
    val crashEventId: String? = null,
)

/**
 * Composes the literal text of a piece of feedback.
 *
 * Pure and separate from the screen for one reason: the screen shows the user exactly
 * this string before it is sent, and the same string is what is sent. If the disclosure
 * were assembled separately from the payload the two would drift, and the "exactly what
 * gets sent" section would become a description of what the app intends to send rather
 * than a copy of it — which is the decorative version of consent this is meant to avoid.
 */
object FeedbackReport {

    /** Enough of the record to show the run-up to a failure without pasting a whole day. */
    const val RECORD_TAIL_LINES = 40

    /**
     * The report as it goes out. [playbackRecord] is the already-trimmed tail of the
     * playback diary, or null when the user has chosen not to attach it — in which case
     * the section is absent rather than empty, so the disclosure and the payload agree.
     */
    fun compose(
        comment: String,
        context: FeedbackContext,
        playbackRecord: String?,
    ): String {
        val text = buildString {
            appendLine(comment.trim())
            appendLine()
            appendLine("--- attached by lugu ---")
            appendLine("App version: ${context.appVersion}")
            appendLine("Device: ${context.deviceModel}")
            appendLine("Android: ${context.androidVersion}")
            appendLine("Playback: ${if (context.playbackActive) "active" else "not active"}")
            appendLine("Player: ${context.playerState}")
            appendLine(
                "Refers to the crash: " +
                    (context.crashEventId ?: "no crash was recorded for the last run"),
            )
            if (playbackRecord != null) {
                appendLine()
                appendLine("--- playback record, last $RECORD_TAIL_LINES lines ---")
                append(playbackRecord.trimEnd())
                appendLine()
            }
        }
        // Applied to the whole payload rather than to the parts, so a line added to any of
        // them later cannot get out with a server address in it.
        return Redaction.scrub(text).trimEnd()
    }

    /** The last [RECORD_TAIL_LINES] lines of the record, or null when there are none. */
    fun tailOf(record: String): String? =
        record.lines()
            .filter { it.isNotBlank() }
            .takeLast(RECORD_TAIL_LINES)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
}
