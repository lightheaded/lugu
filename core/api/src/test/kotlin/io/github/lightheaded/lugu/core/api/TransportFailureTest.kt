package io.github.lightheaded.lugu.core.api

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Test

/**
 * The network stack's words never reach the listener. Each common failure is one plain
 * sentence, and the reserved two lines under the password are enough for it.
 */
class TransportFailureTest {

    @Test
    fun `an address that does not resolve asks for the spelling`() {
        val error = UnknownHostException("Unable to resolve host \"books.example\": No address associated with hostname")
        assertThat(TransportFailure.describe(error)).isEqualTo(TransportFailure.ADDRESS_NOT_FOUND)
    }

    @Test
    fun `a refused connection names the port and the server`() {
        assertThat(TransportFailure.describe(ConnectException("Failed to connect to books.example/:13378")))
            .isEqualTo(TransportFailure.REFUSED)
    }

    @Test
    fun `a timeout is said in seconds`() {
        assertThat(TransportFailure.describe(SocketTimeoutException("timeout"), timeoutMs = 4_000))
            .isEqualTo("Nothing answered within 4 seconds.")
        assertThat(TransportFailure.describe(SocketTimeoutException("timeout")))
            .isEqualTo(TransportFailure.TOO_SLOW)
    }

    @Test
    fun `a failed handshake says the secure connection failed`() {
        assertThat(TransportFailure.describe(SSLHandshakeException("Trust anchor for certification path not found.")))
            .isEqualTo(TransportFailure.SECURE_CONNECTION)
    }

    /** A wrapper is common: the engine's own exception around the Java one. */
    @Test
    fun `the cause is read when the outer exception says nothing useful`() {
        val wrapped = IOException("call failed", UnknownHostException("books.example"))
        assertThat(TransportFailure.describe(wrapped)).isEqualTo(TransportFailure.ADDRESS_NOT_FOUND)
    }

    /** The probe hands over the message only, so the words alone have to be enough. */
    @Test
    fun `a message alone is translated too`() {
        assertThat(TransportFailure.describe("Unable to resolve host \"books.example\"", null))
            .isEqualTo(TransportFailure.ADDRESS_NOT_FOUND)
        assertThat(TransportFailure.describe("failed to connect to books.example after 4000ms", 4_000L))
            .isEqualTo("Nothing answered within 4 seconds.")
        assertThat(TransportFailure.describe("Failed to connect to books.example/:13378", null))
            .isEqualTo(TransportFailure.REFUSED)
        assertThat(TransportFailure.describe("something nobody expected", null))
            .isEqualTo(TransportFailure.NO_ANSWER)
    }
}
