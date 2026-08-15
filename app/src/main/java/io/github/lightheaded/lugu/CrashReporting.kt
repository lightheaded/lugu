package io.github.lightheaded.lugu

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lightheaded.lugu.core.sync.CrashReportingDecision
import io.github.lightheaded.lugu.core.sync.CrashReportingPrefs
import io.github.lightheaded.lugu.core.sync.PlaybackDiary
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Crash reporting, off until someone asks for it.
 *
 * The SDK's own `enabled = false` is not used, and the manifest sets
 * `io.sentry.auto-init` to false: Sentry's documentation says disabling it that way
 * "doesn't prevent all overhead from Sentry instrumentation", and whether a disabled SDK
 * still opens a connection is not something their docs answer. Not initialising it at all
 * is the only version of "no telemetry" that can be verified by reading this file.
 *
 * The cost is real and accepted: a crash before consent is given is lost, because the SDK
 * "can catch errors and crashes only after you've initialized it".
 */
@Singleton
class CrashReporting @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: CrashReportingPrefs,
    private val diary: PlaybackDiary,
) {
    private var started = false

    /** Idempotent, so it can be called on every change of the consent flag. */
    @Synchronized
    fun applyConsent(consentGiven: Boolean) {
        val wanted = CrashReportingDecision.shouldStart(consentGiven, BuildConfig.SENTRY_DSN)
        if (wanted == started) return
        if (wanted) start() else stop()
        started = wanted
    }

    private fun start() {
        SentryAndroid.init(context) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.environment = if (BuildConfig.DEBUG) "debug" else "release"

            // Sessions are pings sent whether or not anything went wrong — telemetry in
            // its own right, and on by default. lugu reports crashes or nothing.
            options.isEnableAutoSessionTracking = false

            // Defaults, set explicitly because they are the promise: no request bodies,
            // no user identifiers, no IP addresses, no device names, no file paths, and
            // no pictures of whatever someone happened to be reading.
            options.isSendDefaultPii = false
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false

            // A crash's event id exists only inside the process that is dying — Android
            // has no `crashedLastRunEventId` (getsentry/sentry-java#2560, open since
            // 2023). Recording it here is what lets the next launch attach someone's
            // description to the crash they actually saw, rather than to a new event.
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                if (event.isCrashed) {
                    runCatching { prefs.recordCrash(event.eventId.toString()) }
                }
                runCatching { attachPlaybackRecord(event) }
                event
            }
        }
    }

    private fun stop() {
        Sentry.close()
    }

    /**
     * Rides the playback record along with an event that was already going.
     *
     * A crash in an audio app is nearly always the end of a sequence — buffering, an
     * output change, a suppression, then the fall over — and a stack trace with no
     * sequence in front of it is a puzzle rather than a report. Attached here in
     * `beforeSend` rather than pushed into the Sentry scope as playback happens, for two
     * reasons: it runs only when something is genuinely being sent, so the diary never
     * touches the player's hot path; and it cannot fire on its own, because there is no
     * path from the diary to the network that does not start with an event.
     *
     * It runs at all only when the SDK has been initialised, which happens only after
     * consent, so no separate check is needed here.
     */
    private fun attachPlaybackRecord(event: SentryEvent) {
        for (entry in diary.entries.value.takeLast(BREADCRUMB_LIMIT)) {
            event.addBreadcrumb(
                Breadcrumb(Date(entry.atMs)).apply {
                    category = "playback"
                    level = SentryLevel.INFO
                    message = Redaction.scrub(
                        if (entry.detail.isNullOrBlank()) {
                            entry.event
                        } else {
                            "${entry.event} — ${entry.detail}"
                        },
                    )
                },
            )
        }
    }

    private companion object {
        /**
         * Enough to cover the run-up to a crash without pushing Sentry's own breadcrumbs
         * out of the report; the SDK trims the list at 100 by default.
         */
        const val BREADCRUMB_LIMIT = 50
    }
}

/**
 * The last thing every outgoing string passes through.
 *
 * lugu talks to a server whose address is the user's own — often a hostname that says
 * where they live or who they work for — and reaches it with a token in a query string.
 * Neither belongs in a crash report or a piece of feedback, and neither is something a
 * person can be expected to spot while reading a diagnostic dump. Nothing here is written
 * expecting to carry a URL; this exists so that a line added carelessly somewhere else
 * cannot turn into a leak.
 *
 * It lives beside the reporter deliberately: the rules about what may leave the device
 * should be readable in one file.
 */
object Redaction {

    /** Replaces any URL with a placeholder, keeping the scheme so the line still reads. */
    fun scrub(text: String): String = URL.replace(text) { match ->
        "${match.groupValues[1]}://<server>"
    }

    private val URL = Regex("""\b(https?)://\S+""", RegexOption.IGNORE_CASE)
}
