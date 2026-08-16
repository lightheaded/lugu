package io.github.lightheaded.lugu.core.sync.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.OkHttpClient

/** The OkHttp instance the live connection uses, as distinct from the one every request uses. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RealtimeHttp

@Module
@InstallIn(SingletonComponent::class)
object RealtimeModule {
    /**
     * The socket shares the app's OkHttp client, with the read timeout removed.
     *
     * Sharing matters: the Socket.IO library brings its own OkHttp, which is older than
     * the one the app pins, and it is excluded at the build level precisely so there is
     * one HTTP stack rather than two. Deriving from the shared client also carries the
     * auth interceptor over, which is what lets the long-poll transport work behind a
     * reverse proxy that expects a bearer token on every request.
     *
     * The timeout has to go because both transports hold a request open indefinitely by
     * design — a long-poll waits for the next event, and a WebSocket has no reply at
     * all. Leaving the default in place would sever a healthy connection every ten
     * seconds and turn the reconnect loop into the steady state.
     */
    @Provides
    @Singleton
    @RealtimeHttp
    fun realtimeOkHttp(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // Liveness is left to Socket.IO's own heartbeat rather than doubled up here.
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()
}
