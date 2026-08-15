package io.github.lightheaded.lugu

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lightheaded.lugu.core.sync.CrashReportingDecision
import io.github.lightheaded.lugu.core.sync.CrashReportingPrefs
import io.sentry.Sentry
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
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
                event
            }
        }
    }

    private fun stop() {
        Sentry.close()
    }
}
