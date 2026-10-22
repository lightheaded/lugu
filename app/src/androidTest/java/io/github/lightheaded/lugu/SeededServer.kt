package io.github.lightheaded.lugu

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * The test server, spoken to directly — for arranging the world, never for checking lugu.
 *
 * Written against `HttpURLConnection` rather than lugu's own client for the reason
 * [NextInSeriesTest]'s progress helper gives: a test that arranges its fixture through the
 * client under test can pass because that client agreed with itself.
 *
 * The address and the credentials come from [TestServerConfig] and nothing here logs them.
 * An error names the method and the kind of call, never the response body: an error page
 * from a server nobody here owns is not something to copy into a CI log.
 */
internal object SeededServer {

    /** A fresh access token for the test account. */
    fun login(): String {
        val body = JSONObject()
            .put("username", TestServerConfig.username)
            .put("password", TestServerConfig.password)
            .toString()
        val user = JSONObject(expect("POST", "/login", token = null, body = body)).getJSONObject("user")
        val token = user.optString("accessToken").ifBlank { user.optString("token") }
        if (token.isBlank()) throw AssertionError("The test server accepted the sign-in but returned no token.")
        return token
    }

    /**
     * The id of the first book whose title contains [query], ignoring case, or null.
     *
     * Asked of every book library on the server, because the seeded server has one and a
     * developer's own may have several.
     */
    fun bookIdByTitle(token: String, query: String): String? {
        val libraries = JSONObject(expect("GET", "/api/libraries", token, body = null))
            .optJSONArray("libraries") ?: return null
        for (l in 0 until libraries.length()) {
            val library = libraries.getJSONObject(l)
            if (library.optString("mediaType") != "book") continue
            val results = JSONObject(expect("GET", "/api/libraries/${library.getString("id")}/items", token, body = null))
                .optJSONArray("results") ?: continue
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val title = item.optJSONObject("media")?.optJSONObject("metadata")?.optString("title").orEmpty()
                if (title.contains(query, ignoreCase = true)) return item.getString("id")
            }
        }
        return null
    }

    /** The account's progress row for one book, or null when it has none. */
    fun progress(token: String, itemId: String): JSONObject? {
        val (code, body) = send("GET", "/api/me/progress/$itemId", token, body = null)
        return when (code) {
            in 200..299 -> JSONObject(body)
            404 -> null
            else -> throw AssertionError("The test server answered $code to GET on a progress call.")
        }
    }

    /** Creates or moves the account's progress for one book. */
    fun setProgress(token: String, itemId: String, currentTimeSec: Double, isFinished: Boolean = false) {
        val body = JSONObject()
            .put("currentTime", currentTimeSec)
            .put("isFinished", isFinished)
            .toString()
        expect("PATCH", "/api/me/progress/$itemId", token, body)
    }

    /**
     * Puts one book back to how [before] found it: the same position, or no row at all.
     *
     * A delete is addressed by the row's own id, not the item's. A delete by item id answers
     * 404, as [NextInSeriesTest] found against a running server.
     */
    fun restoreProgress(token: String, itemId: String, before: JSONObject?) {
        if (before != null) {
            setProgress(token, itemId, before.optDouble("currentTime", 0.0), before.optBoolean("isFinished"))
            return
        }
        val row = progress(token, itemId) ?: return
        val id = row.optString("id")
        if (id.isNotBlank()) expect("DELETE", "/api/me/progress/$id", token, body = null)
    }

    private fun expect(method: String, path: String, token: String?, body: String?): String {
        val (code, text) = send(method, path, token, body)
        if (code !in 200..299) {
            throw AssertionError("The test server answered $code to $method on ${kindOf(path)}.")
        }
        return text
    }

    /** What a call was for, without the ids in its path. */
    private fun kindOf(path: String): String = when {
        path.startsWith("/login") -> "the sign-in"
        path.startsWith("/api/me/progress") -> "a progress call"
        path.startsWith("/api/libraries") -> "a library listing"
        else -> "a call"
    }

    private fun send(method: String, path: String, token: String?, body: String?): Pair<Int, String> {
        val connection = URL(TestServerConfig.url.trimEnd('/') + path).openConnection() as HttpURLConnection
        return try {
            // PATCH is accepted here, unlike on a desktop JVM: Android's HttpURLConnection is
            // an OkHttp underneath, and that one lists PATCH among its methods.
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to (stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private const val TIMEOUT_MS = 15_000
}

/**
 * Signs in through the screen, as [PlaybackResumptionTest] and [NextInSeriesTest] do.
 *
 * The fields are prefilled from `BuildConfig` on a debug build, so this is one tap. A device
 * that is already signed in lands on Home and nothing is tapped.
 */
internal fun ComposeTestRule.signInThroughTheScreen(timeoutMs: Long = 30_000L) {
    waitUntil(timeoutMs) {
        onAllNodes(hasText(SIGN_IN_PROMPT)).fetchSemanticsNodes().isNotEmpty() ||
            onAllNodes(hasContentDescription("Settings")).fetchSemanticsNodes().isNotEmpty()
    }
    if (onAllNodes(hasText(SIGN_IN_PROMPT)).fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithText("Sign in").performClick()
    }
    waitUntil(timeoutMs) {
        onAllNodes(hasContentDescription("Settings")).fetchSemanticsNodes().isNotEmpty()
    }
}

private const val SIGN_IN_PROMPT = "Sign in to your Audiobookshelf server"
