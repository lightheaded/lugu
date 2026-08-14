package io.github.lightheaded.lugu.core.model

/**
 * A server + user pair. Every user-scoped row in the database is keyed by
 * (serverId, userId) from day one so multi-server support is a UI change later,
 * not a migration.
 */
data class ServerAccount(
    val serverId: String,
    val baseUrl: String,
    val userId: String,
    val username: String,
    val defaultLibraryId: String?,
    val serverVersion: String? = null,
)

/**
 * JWT pair from the v2.26+ auth model. Access tokens last ~1h, refresh tokens 30d.
 * Legacy permanent tokens no longer exist, so a client must handle refresh properly.
 */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    /** Wall-clock ms at which the access token expires, as decoded from the JWT. */
    val accessTokenExpiresAtMs: Long,
) {
    fun needsRefresh(nowMs: Long, marginMs: Long = REFRESH_MARGIN_MS): Boolean =
        accessTokenExpiresAtMs - nowMs <= marginMs

    companion object {
        /** Refresh once under five minutes remain, rather than on a 401. */
        const val REFRESH_MARGIN_MS: Long = 5 * 60 * 1000L
    }
}

/** A finished or in-flight listening session, kept locally and uploaded to the server. */
data class ListeningSession(
    /** Client-generated UUID; the server accepts it for offline session upload. */
    val id: String,
    val libraryItemId: String,
    val episodeId: String?,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val startTimeSec: Double,
    val currentTimeSec: Double,
    val timeListeningSec: Double,
    val isLocal: Boolean,
    val uploaded: Boolean,
)
