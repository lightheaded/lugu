package io.github.lightheaded.lugu.core.api

import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * What a screen may say about the installed client certificate.
 *
 * Deliberately only the parts that answer "is this the right file, and will it still work
 * next month": the private key never leaves [ConnectionKeyMaterial], and none of these
 * fields is a secret.
 */
data class ConnectionCertificate(
    val fileName: String,
    val subject: String,
    val issuer: String,
    val expiresAtMs: Long,
)

/** A loaded PKCS#12 file, ready to be installed on an HTTP client. */
class ConnectionKeyPair internal constructor(
    val socketFactory: SSLSocketFactory,
    val trustManager: X509TrustManager,
    val certificate: ConnectionCertificate,
)

/** Raised when a PKCS#12 file cannot be opened, almost always because of the password. */
class ClientCertificateException(message: String) : Exception(message)

/**
 * Turns a PKCS#12 file and its password into the pair OkHttp wants.
 *
 * mTLS is the one server configuration where the connection fails before any of lugu's
 * own error handling gets a look in — the handshake is refused, so there is no status
 * code to explain and nothing useful to show. Supplying the certificate is the only fix.
 */
object ConnectionTls {

    /**
     * @throws ClientCertificateException when the file is not a PKCS#12 keystore, the
     * password does not open it, or it holds no certificate.
     */
    fun load(fileName: String, pkcs12: ByteArray, password: String): ConnectionKeyPair {
        val secret = password.toCharArray()
        val store = try {
            KeyStore.getInstance("PKCS12").apply {
                load(ByteArrayInputStream(pkcs12), secret)
            }
        } catch (e: Exception) {
            // The exception from the JCE names the algorithm that failed, which reads as
            // an app fault. The cause is almost always the password.
            throw ClientCertificateException(
                "That file could not be opened. Check the password, and that it is a .p12 or .pfx file.",
            )
        }

        val keyManagers = try {
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(store, secret) }
                .keyManagers
        } catch (e: Exception) {
            throw ClientCertificateException("That file has no private key lugu can use.")
        }

        // The server is still verified against the device's own trust store: a client
        // certificate says who we are, and changes nothing about who we are willing to
        // talk to.
        val trustManager = platformTrustManager()
        val context = SSLContext.getInstance("TLS").apply {
            init(keyManagers, arrayOf(trustManager), null)
        }

        val leaf = store.aliases().toList()
            .firstNotNullOfOrNull { store.getCertificate(it) as? X509Certificate }
            ?: throw ClientCertificateException("That file holds no certificate.")

        return ConnectionKeyPair(
            socketFactory = context.socketFactory,
            trustManager = trustManager,
            certificate = ConnectionCertificate(
                fileName = fileName,
                subject = leaf.subjectX500Principal.name,
                issuer = leaf.issuerX500Principal.name,
                expiresAtMs = leaf.notAfter.time,
            ),
        )
    }

    internal fun platformTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: error("The platform has no X509 trust manager")
    }
}

/**
 * The client certificate currently in force, as a snapshot any thread can read.
 *
 * A process-wide holder rather than an injected dependency, and that is a decision worth
 * defending. An `SSLSocketFactory` is chosen once, when an HTTP client is built, and is
 * then consulted deep inside connection setup on an arbitrary thread with no coroutine
 * context to suspend in — so it cannot go and ask an encrypted preference file what the
 * certificate is. The alternative, rebuilding every HTTP client whenever the certificate
 * changes, would drop the connection pool and any in-flight download with it.
 *
 * So the factory installed on the client is a fixed delegating one, and this holder is
 * what it delegates to. One certificate at a time, because lugu holds one account at a
 * time; the material is written when it is chosen and cleared when it is removed, and it
 * is never written to disk from here.
 */
object ConnectionKeyMaterial {

    @Volatile private var installed: ConnectionKeyPair? = null

    fun install(pair: ConnectionKeyPair?) {
        installed = pair
    }

    fun certificate(): ConnectionCertificate? = installed?.certificate

    /**
     * Only new connections pick up a change; anything already pooled keeps the material it
     * was opened with. That is the right trade — the alternative is tearing down a
     * download because a setting was edited.
     */
    fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        builder.sslSocketFactory(socketFactory, trustManager)

    /**
     * The same thing for a client this code cannot reach the builder of.
     *
     * The shared OkHttp instance — the one behind the media data source, the downloader and
     * cover loading — is assembled elsewhere, so the certificate is installed per call
     * instead. Returned unchanged when no certificate is configured, which is almost
     * everybody: the connection pool is keyed partly by the socket factory, so leaving the
     * common path untouched keeps it pooling exactly as it did.
     */
    fun applyTo(chain: Interceptor.Chain): Interceptor.Chain =
        if (installed == null) chain else chain.withSslSocketFactory(socketFactory, trustManager)

    private val defaultTrustManager: X509TrustManager by lazy { ConnectionTls.platformTrustManager() }

    private val defaultSocketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf(defaultTrustManager), null) }
            .socketFactory
    }

    private val socketFactory: SSLSocketFactory =
        DelegatingSslSocketFactory { installed?.socketFactory ?: defaultSocketFactory }

    private val trustManager: X509TrustManager =
        DelegatingTrustManager { installed?.trustManager ?: defaultTrustManager }
}

/** Resolves the real factory per socket, so the certificate can change without a new client. */
private class DelegatingSslSocketFactory(
    private val delegate: () -> SSLSocketFactory,
) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate().defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate().supportedCipherSuites

    override fun createSocket(): Socket = delegate().createSocket()

    override fun createSocket(socket: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
        delegate().createSocket(socket, host, port, autoClose)

    override fun createSocket(host: String?, port: Int): Socket = delegate().createSocket(host, port)

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        delegate().createSocket(host, port, localHost, localPort)

    override fun createSocket(host: InetAddress?, port: Int): Socket = delegate().createSocket(host, port)

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = delegate().createSocket(address, port, localAddress, localPort)
}

/** The server side of the same indirection; it never trusts anything the platform would not. */
private class DelegatingTrustManager(
    private val delegate: () -> X509TrustManager,
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        delegate().checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        delegate().checkServerTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate().acceptedIssuers
}
