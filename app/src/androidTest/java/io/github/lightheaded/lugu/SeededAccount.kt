package io.github.lightheaded.lugu

import android.content.Context
import io.github.lightheaded.lugu.core.db.ServerDao
import io.github.lightheaded.lugu.core.model.AuthTokens
import io.github.lightheaded.lugu.core.sync.EncryptedTokenStore
import kotlinx.coroutines.runBlocking

/**
 * A signed-in account, without a server and without signing in.
 *
 * A row in `server` is **not** enough to make lugu consider itself signed in. `isSignedIn`
 * is `serverDao.active() != null && tokenStore.tokens() != null`, and the token lives in
 * encrypted preferences rather than in Room — so a test that seeds only the database gets
 * the login screen, and every assertion about the app then fails somewhere far away from
 * the reason. [AutoBrowseTreeTest] does not need this because it binds a `MediaBrowser` and
 * never goes through the UI; anything that drives a screen does.
 *
 * Found the hard way: the first run of the grid tests passed on a device that still had a
 * real token from an earlier suite, and every run after the app was reinstalled failed at
 * "there is no Library tab" — which is true, and says nothing about why.
 *
 * The token is nonsense on purpose. Nothing here may reach a server: every test that uses
 * this points its account at a domain that cannot resolve, so a token that would be
 * rejected is the honest thing to store. Whatever the device already held is handed back by
 * [restore], so a phone signed in to a real server is not signed out by running the tests.
 */
internal class PlantedToken private constructor(
    private val store: EncryptedTokenStore,
    private val serverId: String,
    private val displaced: AuthTokens?,
) {

    fun restore() = runBlocking {
        if (displaced == null) store.clearFor(serverId) else store.saveFor(serverId, displaced)
    }

    companion object {
        /** Far enough ahead that nothing treats it as expired mid-test. */
        private const val AN_HOUR_MS = 60L * 60L * 1_000L

        /**
         * Plants a token **for [serverId]**, and not for whichever account is active.
         *
         * The account matters now, and it did not before. The store used to hold one set
         * of tokens for the whole install; it holds one set per account, and every test
         * that uses this plants its token *before* it makes its own server row active. So
         * a plant aimed at the active account would land on whatever the device was signed
         * in to, the test's own account would have no token, and `isSignedIn` would be
         * false — which is the login screen, and every assertion then failing a long way
         * from the reason. That is the same failure this file's own KDoc was written about,
         * arriving by a new route.
         *
         * Caught by CI on 3 September 2026: `:app:compileDebugAndroidTestKotlin` failed on
         * the added constructor parameter, which is the cheap half. The semantic half above
         * would have compiled and gone red on three emulators.
         */
        fun plant(context: Context, serverDao: ServerDao, serverId: String): PlantedToken {
            val store = EncryptedTokenStore(context, serverDao)
            return runBlocking {
                val displaced = store.tokensFor(serverId)
                store.saveFor(
                    serverId,
                    AuthTokens(
                        accessToken = "instrumented-test-token",
                        refreshToken = null,
                        accessTokenExpiresAtMs = System.currentTimeMillis() + AN_HOUR_MS,
                    ),
                )
                PlantedToken(store, serverId, displaced)
            }
        }
    }
}
