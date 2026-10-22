package io.github.lightheaded.lugu.core.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The address and the username of the last sign-in that worked. */
data class LastSignIn(val baseUrl: String, val username: String)

/**
 * Remembers where the last sign-in went, so the sign-in screen does not start empty.
 *
 * The account row cannot do this job. It holds the same two values, but a sign-out deletes
 * it, and an empty address field after a sign-out was the fault (issue #12). This copy is
 * outside the row, so it survives the sign-out, and it goes only when somebody asks the
 * sign-in screen to forget the server.
 *
 * Plain [android.content.SharedPreferences] rather than the encrypted store. Neither value
 * is a secret: both already sit in the clear in Room, on the account row, and the address
 * is printed on the Accounts screen. The encrypted store can also come back empty after a
 * restore, and a store that can lose the address is a poor place to remember it.
 *
 * Synchronous on purpose. The sign-in screen reads it while it builds its first state, and
 * the fields must not fill in a frame later under a thumb that already reaches for them.
 */
@Singleton
class SignInMemory @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun last(): LastSignIn? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        return LastSignIn(baseUrl = baseUrl, username = prefs.getString(KEY_USERNAME, null).orEmpty())
    }

    fun remember(baseUrl: String, username: String) {
        prefs.edit().putString(KEY_BASE_URL, baseUrl).putString(KEY_USERNAME, username).apply()
    }

    fun forget() {
        prefs.edit().remove(KEY_BASE_URL).remove(KEY_USERNAME).apply()
    }

    private companion object {
        const val FILE_NAME = "lugu_last_sign_in"
        const val KEY_BASE_URL = "base_url"
        const val KEY_USERNAME = "username"
    }
}
