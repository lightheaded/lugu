package io.github.lightheaded.lugu.core.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the crash reporter may run at all.
 *
 * Pure, so the one invariant that matters can be tested without an Android runtime:
 * lugu tells people it collects nothing unless asked, and that promise is only as good
 * as the check enforcing it.
 */
object CrashReportingDecision {
    fun shouldStart(consentGiven: Boolean, dsn: String): Boolean =
        consentGiven && dsn.isNotBlank()
}

/**
 * Consent for crash reporting, and the bookkeeping that survives a crash.
 *
 * Backed by [android.content.SharedPreferences] rather than DataStore, which is the odd
 * one out in this codebase and deliberately so. Two things need to happen where DataStore
 * cannot follow:
 *
 *  - the flag is read in `Application.onCreate` before anything is allowed to suspend,
 *    because a reporter started late misses precisely the startup crashes that are
 *    hardest to reproduce;
 *  - the id of a crash is written from inside the process that is dying, where an
 *    asynchronous write simply does not land.
 */
@Singleton
class CrashReportingPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("lugu_diagnostics", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))

    /** Observed by the Application, so the toggle takes effect without a restart. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Off unless someone has said otherwise. Never change this default. */
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    /**
     * Called from Sentry's `beforeSend` while the process is crashing, so it commits
     * synchronously — `apply()` would be scheduled onto a process that is about to stop
     * existing. Android has no `crashedLastRunEventId`, so this is the only way the next
     * launch can attach someone's description to the crash they actually saw.
     */
    fun recordCrash(eventId: String) {
        prefs.edit().putString(KEY_LAST_CRASH, eventId).commit()
    }

    /** Event id of the last crash, or null if the previous run ended cleanly. */
    fun lastCrashEventId(): String? = prefs.getString(KEY_LAST_CRASH, null)

    /** Called once the crash has been acknowledged, so it is asked about only once. */
    fun clearLastCrash() {
        prefs.edit().remove(KEY_LAST_CRASH).apply()
    }

    /**
     * The crash the launch prompt has already been shown for, or null if it has never
     * been shown. Stored as an id rather than a flag so that a *second*, different crash
     * still gets asked about — see [CrashPromptDecision].
     */
    fun askedAboutKey(): String? = prefs.getString(KEY_ASKED_ABOUT, null)

    fun markAsked(key: String) {
        prefs.edit().putString(KEY_ASKED_ABOUT, key).apply()
    }

    private companion object {
        const val KEY_ENABLED = "crash_reporting_enabled"
        const val KEY_LAST_CRASH = "last_crash_event_id"
        const val KEY_ASKED_ABOUT = "asked_about_crash"
    }
}

/**
 * Whether to offer the post-crash prompt on this launch, and about which crash.
 *
 * The failure mode this exists to prevent is a crash loop: an app that falls over on
 * every launch would otherwise ask about it on every launch, which is nagging in the
 * precise moment someone is least inclined to be helpful. Keying on the event id means
 * the same crash is offered once and a genuinely new one is still offered.
 *
 * Pure, so the loop case can be tested without a crash.
 */
object CrashPromptDecision {

    /**
     * The key to ask about, or null to stay quiet.
     *
     * Note that with crash reporting off the SDK is never initialised, so
     * `Sentry.isCrashedLastRun()` is null and no id is ever recorded — the prompt does
     * not appear at all. That is correct rather than a gap: with nothing to send there is
     * nothing to offer.
     */
    fun keyToAskAbout(
        crashedLastRun: Boolean,
        lastCrashEventId: String?,
        alreadyAskedKey: String?,
    ): String? {
        // An id is the good case. Without one there is nothing to tell this crash from
        // the next, so the fixed key asks once and then stays quiet — the wrong side to
        // err on would be asking forever.
        val key = lastCrashEventId ?: if (crashedLastRun) UNIDENTIFIED else return null
        return key.takeIf { it != alreadyAskedKey }
    }

    /** Used when Sentry reports a crash it has no recorded id for. */
    const val UNIDENTIFIED = "unidentified-crash"
}
