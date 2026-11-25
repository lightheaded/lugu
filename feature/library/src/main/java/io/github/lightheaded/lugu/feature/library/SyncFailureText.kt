package io.github.lightheaded.lugu.feature.library

import io.github.lightheaded.lugu.core.api.AuthExpiredException

/**
 * What the status line says when a sync could not run because the sign-in ended.
 *
 * The raw message was "Not signed in", which reads as if the library were gone. It is not:
 * the mirror still reads and the downloads still play. The note says so, and says where
 * the sign-in is, because the screen that offered it may already have been dismissed.
 */
internal const val SESSION_ENDED_NOTE =
    "Your session ended. Downloads still play. To sync, sign in again in Settings → Accounts."

/**
 * The same for an action somebody pressed: a download, a change to a collection, a fetch
 * of new episodes. It names no action, because one sentence serves every button, and it
 * says "this" so that it reads as the answer to the press that showed it.
 */
internal const val SESSION_ENDED_ACTION_NOTE =
    "Your session ended. To do this, sign in again in Settings → Accounts. Downloads still play."

/** The text for a failed sync: the ended session in words, anything else as it came. */
internal fun syncFailureText(failure: Throwable, fallback: String): String =
    if (failure.isSessionEnded()) SESSION_ENDED_NOTE else failure.message ?: fallback

/** The text for an action that failed, by the same rule as [syncFailureText]. */
internal fun actionFailureText(failure: Throwable, fallback: String): String =
    if (failure.isSessionEnded()) SESSION_ENDED_ACTION_NOTE else failure.message ?: fallback

/**
 * Only a real lapse. A sign-in that changed during a refresh is a moment's race, and no
 * account at all is not an ended session: telling either one that "your session ended"
 * would be untrue. Those keep their own words.
 */
private fun Throwable.isSessionEnded(): Boolean = this is AuthExpiredException && isSessionEnded
