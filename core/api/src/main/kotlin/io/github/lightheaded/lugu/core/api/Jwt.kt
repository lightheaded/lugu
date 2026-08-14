package io.github.lightheaded.lugu.core.api

/**
 * Just enough JWT handling to know when an access token expires.
 *
 * Hand-rolled base64url rather than `java.util.Base64` so this module stays free of
 * JVM-only APIs (see the KMP-ready rule in docs/EXECUTION-PLAN.md Phase 1).
 * The signature is never checked here — that is the server's job; lugu only reads
 * `exp` so it can refresh before a request fails rather than after.
 */
object Jwt {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Expiry in epoch milliseconds, or null if the token is unreadable. */
    fun expiresAtMs(token: String): Long? {
        val payload = token.split('.').getOrNull(1) ?: return null
        val json = decodeBase64Url(payload)?.decodeToString() ?: return null
        val exp = Regex("\"exp\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()
        return exp?.times(1000L)
    }

    internal fun decodeBase64Url(input: String): ByteArray? {
        val clean = input.trimEnd('=')
        if (clean.any { ALPHABET.indexOf(it) < 0 }) return null

        val out = ArrayList<Byte>(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (char in clean) {
            buffer = (buffer shl 6) or ALPHABET.indexOf(char)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
