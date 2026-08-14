package io.github.lightheaded.lugu.core.api

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the bearer token to media and cover requests.
 *
 * Audio streaming and cover art go through OkHttp directly rather than through
 * [AbsClient], but they still need auth — and a 40-hour book outlives a one-hour
 * access token, so the header has to be resolved per request rather than baked into a
 * data-source factory once. Signed URLs (`?token=`) were the alternative and they
 * expire mid-download, which is exactly the failure this avoids.
 *
 * Only requests aimed at the configured server are touched; nothing else gets a token.
 */
class AuthInterceptor(
    private val serverUrlProvider: ServerUrlProvider,
    private val tokenProvider: suspend () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null) return chain.proceed(request)

        // OkHttp interceptors are blocking by contract and run on a background thread.
        val base = runBlocking { runCatching { serverUrlProvider.baseUrl() }.getOrNull() }
        if (base == null || !request.url.toString().startsWith(base)) return chain.proceed(request)

        val token = runBlocking { runCatching { tokenProvider() }.getOrNull() }
            ?: return chain.proceed(request)

        return chain.proceed(
            request.newBuilder().header("Authorization", "Bearer $token").build(),
        )
    }
}
