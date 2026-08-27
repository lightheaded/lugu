package io.github.lightheaded.lugu.core.sync

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lightheaded.lugu.core.api.ClientCertificateException
import io.github.lightheaded.lugu.core.api.ConnectionCertificate
import io.github.lightheaded.lugu.core.api.ConnectionHeader
import io.github.lightheaded.lugu.core.api.ConnectionHeaders
import io.github.lightheaded.lugu.core.api.ConnectionKeyMaterial
import io.github.lightheaded.lugu.core.api.ConnectionProfile
import io.github.lightheaded.lugu.core.api.ConnectionTls
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The custom headers and the client certificate, at rest.
 *
 * Both are credentials, so they live where the tokens live — [EncryptedTokenStore] sets
 * the pattern and states the reason, including why the deprecated library stays. A
 * Cloudflare Access client secret is exactly as valuable as a refresh token, and rather
 * longer-lived: putting it in Room, where it would be readable by anything that can read
 * the database file and would be copied into every backup of it, would undo the reason the
 * token store exists.
 *
 * Nothing here is ever logged, attached to feedback or written to the playback record.
 * The types it hands out mask themselves when printed, which is the backstop for the
 * accidental case rather than the deliberate one.
 *
 * Headers are keyed by server address rather than by server id on purpose. The id is
 * `address#user`, and it does not exist until a sign-in has succeeded — but somebody
 * behind an identity-aware proxy cannot reach `/status`, let alone sign in, without their
 * headers. Keying by the address they typed is what makes them settable first.
 *
 * The certificate is not keyed at all. lugu holds one account, an `SSLSocketFactory` is
 * chosen when a connection is opened rather than when a request is routed, and one
 * certificate at a time is what that machinery can honestly support.
 *
 * Every read goes through [SecurePrefs], so a store that cannot be decrypted reads as "no
 * headers and no certificate" rather than as a throw. That answer is the safe one of the
 * two: it makes a request that a proxy refuses, and a refusal is recoverable. A throw here
 * would instead reach the settings screen and the request path together.
 */
@Singleton
class ConnectionPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
    losses: CredentialLossReport = CredentialLossReport(),
) {

    @Serializable
    private data class StoredHeader(val name: String, val value: String)

    /**
     * Bumped on every write. The screens want to redraw when a header is added, and an
     * encrypted preference file has no change notification worth the name.
     */
    private val revision = MutableStateFlow(0)

    private val prefs = SecurePrefs(
        context = context,
        fileName = FILE_NAME,
        kind = CredentialKind.ConnectionSettings,
        losses = losses,
        // Runs on the file that opened, which is what restores a stored certificate on a
        // cold start.
        onOpen = ::restoreCertificate,
    )

    // region headers

    fun headers(baseUrl: String): List<ConnectionHeader> = read(headerKey(baseUrl))

    /** Redraws whenever anything here changes; the caller filters to the address it cares about. */
    fun observeHeaders(baseUrl: String): Flow<List<ConnectionHeader>> =
        revision.map { headers(baseUrl) }

    fun setHeaders(baseUrl: String, headers: List<ConnectionHeader>) {
        val cleaned = ConnectionHeaders.deduplicate(headers.filter { it.name.isNotBlank() })
        val key = headerKey(baseUrl)
        prefs.write {
            if (cleaned.isEmpty()) {
                remove(key)
            } else {
                putString(key, json.encodeToString(SERIALIZER, cleaned.map { StoredHeader(it.name, it.value) }))
            }
        }
        revision.value++
    }

    /**
     * The profile for an address nothing is signed in to yet — the login screen's case.
     * The longest matching address wins, so a subpath install does not inherit the
     * headers of whatever else is on the same host.
     */
    fun profileFor(url: String): ConnectionProfile? {
        val match = storedAddresses()
            .filter { it.isNotEmpty() && url.startsWith(it) }
            .maxByOrNull { it.length }
            ?: return null
        return ConnectionProfile(addresses = listOf(match), headers = read(headerKey(match)))
    }

    /** Every address that has headers stored, so the connection screen can offer to forget them. */
    fun configuredAddresses(): List<String> = storedAddresses().sorted()

    /**
     * Reading `all` decrypts every key in the file, so it is the read most likely to meet a
     * keyset that no longer matches. An empty list is the answer when it does.
     */
    private fun storedAddresses(): List<String> = prefs.read(emptyList<String>()) { store ->
        store.all.keys
            .filter { it.startsWith(HEADERS) }
            .map { it.removePrefix(HEADERS) }
    }

    private fun read(key: String): List<ConnectionHeader> {
        val raw = prefs.read<String?>(null) { it.getString(key, null) } ?: return emptyList()
        return runCatching {
            json.decodeFromString(SERIALIZER, raw).map { ConnectionHeader(it.name, it.value) }
        }.getOrDefault(emptyList())
    }

    private fun headerKey(baseUrl: String) = HEADERS + baseUrl.trimEnd('/')

    // endregion

    // region client certificate

    fun certificate(): ConnectionCertificate? =
        if (prefs.read(false) { it.contains(KEY_CERT) }) ConnectionKeyMaterial.certificate() else null

    fun observeCertificate(): Flow<ConnectionCertificate?> = revision.map { certificate() }

    /**
     * Reads the file the system picker returned, checks the password by actually opening
     * the keystore, and only then stores it. Storing first and failing later would leave a
     * certificate installed that cannot be used and cannot be explained.
     *
     * A store that refuses the write fails the whole call for the same reason. A
     * certificate that works now and is gone after a restart is the harder fault to report.
     */
    suspend fun installCertificate(uri: Uri, password: String): Result<ConnectionCertificate> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw ClientCertificateException("That file could not be read.")
                val name = displayName(uri)
                val pair = ConnectionTls.load(name, bytes, password)

                val stored = prefs.write {
                    putString(KEY_CERT, Base64.encodeToString(bytes, Base64.NO_WRAP))
                    putString(KEY_CERT_PASSWORD, password)
                    putString(KEY_CERT_NAME, name)
                }
                if (!stored) {
                    throw ClientCertificateException("That certificate could not be stored on this device.")
                }
                ConnectionKeyMaterial.install(pair)
                revision.value++
                pair.certificate
            }
        }

    fun removeCertificate() {
        prefs.write {
            remove(KEY_CERT)
            remove(KEY_CERT_PASSWORD)
            remove(KEY_CERT_NAME)
        }
        ConnectionKeyMaterial.install(null)
        revision.value++
    }

    /**
     * Puts the stored certificate back into force the first time anything asks about the
     * connection, which is before the first request goes out: every request resolves its
     * profile through here first, and a socket is opened after that.
     */
    private fun restoreCertificate(source: SharedPreferences) {
        runCatching {
            val encoded = source.getString(KEY_CERT, null) ?: return
            val password = source.getString(KEY_CERT_PASSWORD, null) ?: return
            val name = source.getString(KEY_CERT_NAME, null).orEmpty()
            ConnectionKeyMaterial.install(
                ConnectionTls.load(name, Base64.decode(encoded, Base64.NO_WRAP), password),
            )
        }
    }

    // endregion

    /** Forgets everything stored for one address. Offered on sign-out and on the screen itself. */
    fun forget(baseUrl: String) {
        prefs.write { remove(headerKey(baseUrl)) }
        revision.value++
    }

    private fun displayName(uri: Uri): String {
        val fromResolver = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        return fromResolver ?: uri.lastPathSegment?.substringAfterLast('/') ?: "certificate"
    }

    private companion object {
        const val FILE_NAME = "lugu_connection"
        const val HEADERS = "headers:"
        const val KEY_CERT = "client_certificate"
        const val KEY_CERT_PASSWORD = "client_certificate_password"
        const val KEY_CERT_NAME = "client_certificate_name"

        val json = Json { ignoreUnknownKeys = true }
        val SERIALIZER = ListSerializer(StoredHeader.serializer())
    }
}
