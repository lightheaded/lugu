package io.github.lightheaded.lugu.core.api

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * The certificate path has one failure that matters more than the rest: a wrong password.
 * It is the common case, the message has to say so, and the message must not contain the
 * password it is complaining about — an exception message is the string most likely to end
 * up in a report.
 */
class ConnectionTlsTest {

    @Test
    fun `a file that is not a keystore is refused with a message about the password`() {
        val failure = runCatching {
            ConnectionTls.load("mine.p12", "not a keystore at all".toByteArray(), "hunter2")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ClientCertificateException::class.java)
        assertThat(failure).hasMessageThat().contains("password")
    }

    @Test
    fun `the failure message does not repeat the password`() {
        val failure = runCatching {
            ConnectionTls.load("mine.p12", byteArrayOf(1, 2, 3), "correct-horse-battery-staple")
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().doesNotContain("correct-horse-battery-staple")
    }

    /**
     * With nothing installed the delegating factory has to behave exactly like the platform
     * default, because it is on the client whether or not anybody ever picks a certificate.
     */
    @Test
    fun `a client with no certificate installed still builds and trusts the platform`() {
        ConnectionKeyMaterial.install(null)

        val client = ConnectionKeyMaterial.applyTo(OkHttpClient.Builder()).build()

        assertThat(ConnectionKeyMaterial.certificate()).isNull()
        assertThat(client.sslSocketFactory.supportedCipherSuites).isNotEmpty()
        assertThat(client.x509TrustManager?.acceptedIssuers).isNotEmpty()
    }
}
