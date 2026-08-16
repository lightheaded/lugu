package io.github.lightheaded.lugu.core.api

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the bearer token, and any custom headers, to media and cover requests.
 *
 * Audio streaming and cover art go through OkHttp directly rather than through
 * [AbsClient], but they still need auth — and a 40-hour book outlives a one-hour
 * access token, so the header has to be resolved per request rather than baked into a
 * data-source factory once. Signed URLs (`?token=`) were the alternative and they
 * expire mid-download, which is exactly the failure this avoids.
 *
 * The custom headers ride the same path for the same reason. This interceptor sits on the
 * one OkHttp client that serves the media data source, the downloader and Coil's cover
 * loading, so a header added on the connection screen reaches all three. Applying them
 * only to the API would give somebody behind an identity-aware proxy a library that
 * browses and a book that will not play, which is the harder bug to report.
 *
 * Only requests aimed at the configured server are touched; nothing else gets a token or a
 * header. [connectionProfiles] decides what "the configured server" means, and it knows
 * about both the address the account was created with and the local-network address, so a
 * URL built from either one is recognised.
 */
class AuthInterceptor(
    serverUrlProvider: ServerUrlProvider,
    /**
     * Defaulted, and resolved from [serverUrlProvider] when it can be, because the shared
     * OkHttp client is assembled in a dependency-injection module that passes only the URL
     * provider. A provider that knows nothing about connection settings falls back to the
     * behaviour this interceptor had before they existed rather than losing its token.
     */
    private val connectionProfiles: ConnectionProfileSource =
        serverUrlProvider as? ConnectionProfileSource ?: ServerUrlProfileSource(serverUrlProvider),
    private val tokenProvider: suspend () -> String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // OkHttp interceptors are blocking by contract and run on a background thread.
        val profile = runBlocking {
            runCatching { connectionProfiles.profileFor(request.url.toString()) }.getOrNull()
        } ?: return chain.proceed(request)

        val builder = request.newBuilder()

        // A header the caller set explicitly wins: the download manifest and the probe
        // both build their own requests, and silently overwriting them would be worse than
        // useless to debug.
        profile.headers.forEach { header ->
            if (request.header(header.name) == null) builder.header(header.name, header.value)
        }

        if (request.header("Authorization") == null) {
            val token = runBlocking { runCatching { tokenProvider() }.getOrNull() }
            if (token != null) builder.header("Authorization", "Bearer $token")
        }

        // A client certificate cannot be attached to a request, only to a connection, and
        // this client's builder is out of reach — so it goes on the chain instead. Without
        // this, a server behind mutual TLS would browse through the API client and refuse
        // the handshake for every cover and every second of audio.
        return ConnectionKeyMaterial.applyTo(chain).proceed(builder.build())
    }
}
