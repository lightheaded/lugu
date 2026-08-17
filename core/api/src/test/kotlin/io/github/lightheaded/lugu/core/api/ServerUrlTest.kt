package io.github.lightheaded.lugu.core.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ServerUrlTest {

    @Test
    fun `a bare host gets https`() {
        assertThat(ServerUrl.normalise("books.example.com")).isEqualTo("https://books.example.com")
    }

    @Test
    fun `an explicit scheme is kept`() {
        assertThat(ServerUrl.normalise("http://192.168.1.10:13378"))
            .isEqualTo("http://192.168.1.10:13378")
    }

    @Test
    fun `an auto-capitalised scheme is accepted rather than blamed on the typist`() {
        assertThat(ServerUrl.normalise("Https://books.example.com"))
            .isEqualTo("https://books.example.com")
        // And only the scheme is lowered: a path may be case-sensitive.
        assertThat(ServerUrl.normalise("HTTPS://example.com/AudioBookshelf"))
            .isEqualTo("https://example.com/AudioBookshelf")
    }

    @Test
    fun `trailing slashes and whitespace are trimmed`() {
        assertThat(ServerUrl.normalise("  https://books.example.com/  "))
            .isEqualTo("https://books.example.com")
    }

    @Test
    fun `a login path pasted from a browser is stripped`() {
        assertThat(ServerUrl.normalise("https://books.example.com/login"))
            .isEqualTo("https://books.example.com")
    }

    @Test
    fun `a subpath install is preserved`() {
        assertThat(ServerUrl.normalise("https://example.com/audiobookshelf"))
            .isEqualTo("https://example.com/audiobookshelf")
    }

    @Test
    fun `a plain HTTP address is recognised so it can be warned about`() {
        assertThat(ServerUrl.isCleartext("http://192.168.1.10:13378")).isTrue()
        assertThat(ServerUrl.isCleartext("HTTP://192.168.1.10:13378")).isTrue()
        assertThat(ServerUrl.isCleartext("https://books.example.com")).isFalse()
        // A bare host becomes https, so it is not a plain-HTTP address.
        assertThat(ServerUrl.isCleartext("192.168.1.10:13378")).isFalse()
    }

    @Test
    fun `typing towards https does not flash a warning`() {
        // Each of these is a keystroke on the way to "https://books.example.com".
        assertThat(ServerUrl.isCleartext("h")).isFalse()
        assertThat(ServerUrl.isCleartext("http")).isFalse()
        assertThat(ServerUrl.isCleartext("http:")).isFalse()
        assertThat(ServerUrl.isCleartext("http://")).isFalse()
    }

    @Test
    fun `nonsense is rejected rather than guessed at`() {
        assertThat(ServerUrl.normalise("")).isNull()
        assertThat(ServerUrl.normalise("   ")).isNull()
        assertThat(ServerUrl.normalise("ftp://books.example.com")).isNull()
        assertThat(ServerUrl.normalise("https://")).isNull()
    }
}

class JwtTest {

    /**
     * Header and signature are irrelevant here — only the payload is read, and only
     * for `exp`. The payload below decodes to {"exp":1786000000,"sub":"u"}.
     */
    private fun tokenWith(payload: String): String {
        val encoded = base64Url(payload.encodeToByteArray())
        return "header.$encoded.signature"
    }

    private fun base64Url(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 6) {
                bits -= 6
                out.append(alphabet[(buffer shr bits) and 0x3F])
            }
        }
        if (bits > 0) out.append(alphabet[(buffer shl (6 - bits)) and 0x3F])
        return out.toString()
    }

    @Test
    fun `reads exp as epoch milliseconds`() {
        val token = tokenWith("""{"exp":1786000000,"sub":"u"}""")
        assertThat(Jwt.expiresAtMs(token)).isEqualTo(1_786_000_000_000L)
    }

    @Test
    fun `survives a token with no exp claim`() {
        assertThat(Jwt.expiresAtMs(tokenWith("""{"sub":"u"}"""))).isNull()
    }

    @Test
    fun `survives a malformed token instead of throwing`() {
        assertThat(Jwt.expiresAtMs("not-a-jwt")).isNull()
        assertThat(Jwt.expiresAtMs("")).isNull()
        assertThat(Jwt.expiresAtMs("a.!!!!.c")).isNull()
    }

    @Test
    fun `base64url round trips through the decoder`() {
        val payload = """{"exp":1786000000}"""
        val decoded = Jwt.decodeBase64Url(base64Url(payload.encodeToByteArray()))
        assertThat(decoded?.decodeToString()).isEqualTo(payload)
    }
}
