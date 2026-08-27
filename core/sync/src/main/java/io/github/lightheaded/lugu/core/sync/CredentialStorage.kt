package io.github.lightheaded.lugu.core.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What one encrypted file holds, so a message can name what is gone. */
enum class CredentialKind {
    /** The access token and the refresh token. Loss means a new sign-in. */
    Tokens,

    /** The custom headers and the client certificate. Loss means the server is unreachable. */
    ConnectionSettings,
}

/**
 * Records that encrypted storage was rebuilt, and that what it held is gone.
 *
 * A read that cannot decrypt has two possible outcomes for the listener. The first is a
 * crash, and the second is a login screen with no explanation. Both are wrong. This class
 * makes the third outcome possible: a login screen that says why.
 *
 * One instance for the whole app, because two stores share one master key. A failure in
 * either is the same event to the person who has to sign in again.
 */
@Singleton
class CredentialLossReport @Inject constructor() {

    private val _lost = MutableStateFlow<Set<CredentialKind>>(emptySet())

    /** What is gone. Empty in the normal case, and empty again after [acknowledge]. */
    val lost: StateFlow<Set<CredentialKind>> = _lost.asStateFlow()

    fun record(kind: CredentialKind) = _lost.update { it + kind }

    /** Called when the message was shown. The loss is history after that. */
    fun acknowledge() = _lost.update { emptySet() }
}

/**
 * Says what was lost, and what to do about it, in the listener's terms.
 *
 * The cause is named because it is not the app's fault and not the listener's: a device
 * restore or a lock-screen change replaces the key that the store was built on. A
 * certificate gets its own sentence, because nobody can recall one from memory.
 *
 * The words live here rather than on a screen, because two screens ask for them. The
 * sign-in screen says why it appeared, and the settings screen says why a connection
 * detail is missing. One wording keeps them from drifting apart.
 */
fun credentialLossMessage(lost: Set<CredentialKind>): String? {
    val tokens = CredentialKind.Tokens in lost
    val connection = CredentialKind.ConnectionSettings in lost
    return when {
        tokens && connection ->
            "This device replaced the key that protects stored credentials, which happens " +
                "after a restore. The sign-in and the connection settings are gone. Sign in " +
                "again, and add any custom headers or client certificate again."
        tokens ->
            "This device replaced the key that protects the stored sign-in, which happens " +
                "after a restore. Please sign in again."
        connection ->
            "This device replaced the key that protects the connection settings, which " +
                "happens after a restore. Add any custom headers or client certificate again."
        else -> null
    }
}

/** The next step after an attempt to open or read encrypted storage failed. */
enum class CredentialRepair {
    /**
     * The storage was busy, not broken. Keep the stored bytes and ask again later.
     * A full disk must never cost the listener their session.
     */
    WaitAndRetry,

    /** The stored bytes cannot be decrypted. Delete the file, and open a new one. */
    DeleteFile,

    /** A new file did not help, so the master key is unusable. Make a new key. */
    ReplaceKey,

    /** Nothing more to try. Report the loss, and answer every read with nothing. */
    GiveUp,
}

/**
 * Sorts a storage failure into a repair step.
 *
 * Pure on purpose. The repair itself needs a device, but the decision does not, and the
 * decision is the part that can be wrong. See `CredentialStoreRepairTest`.
 *
 * The two failure types come from the artifact rather than from memory.
 * `EncryptedSharedPreferences.create` declares `GeneralSecurityException` and
 * `IOException`. Its private `getDecryptedObject` declares `SecurityException`, which is
 * unchecked, and which every getter can therefore throw. A lock-screen change or a backup
 * restore arrives as `KeyPermanentlyInvalidatedException`, a subclass of
 * `GeneralSecurityException`.
 */
internal object CredentialStoreRepair {

    /**
     * @param attempt how many repairs were already tried. 0 is the first failure.
     */
    fun next(error: Throwable, attempt: Int): CredentialRepair {
        val chain = chain(error)
        return when {
            // A crypto failure inside an IOException is still a crypto failure. Tink wraps
            // keyset problems, so the cause matters more than the outermost type.
            chain.any { it.losesData() } -> ladder(attempt)
            chain.any { it is IOException } -> CredentialRepair.WaitAndRetry
            // An unfamiliar failure destroys nothing. A wipe on a guess is the one mistake
            // here that cannot be undone.
            else -> CredentialRepair.GiveUp
        }
    }

    private fun ladder(attempt: Int) = when (attempt) {
        0 -> CredentialRepair.DeleteFile
        1 -> CredentialRepair.ReplaceKey
        else -> CredentialRepair.GiveUp
    }

    private fun Throwable.losesData() = this is GeneralSecurityException || this is SecurityException

    /** The error and its causes. Capped, because a cause chain can point at itself. */
    private fun chain(error: Throwable): List<Throwable> {
        val found = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && found.size < MAX_CAUSE_DEPTH) {
            found += current
            current = current.cause.takeIf { it !== current }
        }
        return found
    }

    private const val MAX_CAUSE_DEPTH = 8
}

/**
 * An encrypted preference file that never throws at its caller.
 *
 * Every method answers a fallback instead. That is the whole point: the store sits under
 * the startup check, so one throw here is a crash on every launch, and no launch after it
 * can clear the condition. The listener sees an app that dies on the splash screen.
 *
 * A failure that cannot be repaired is reported to [CredentialLossReport], so the screen
 * that asks for a password again can say why it asks.
 *
 * @param onOpen runs once, on the file that opened. Used to put stored key material back
 *   into force before the first request goes out.
 */
@Suppress("DEPRECATION")
internal class SecurePrefs(
    private val context: Context,
    private val fileName: String,
    private val kind: CredentialKind,
    private val losses: CredentialLossReport,
    private val onOpen: (SharedPreferences) -> Unit = {},
) {

    private val lock = Any()

    @Volatile
    private var opened: SharedPreferences? = null

    @Volatile
    private var closedForGood = false

    /** The open file, or null when it cannot be opened. */
    fun openOrNull(): SharedPreferences? {
        opened?.let { return it }
        if (closedForGood) return null
        return synchronized(lock) { openWhileLocked() }
    }

    /** The open attempt and the repairs after it. Call under [lock] only. */
    private fun openWhileLocked(): SharedPreferences? {
        opened?.let { return it }
        if (closedForGood) return null
        for (attempt in 0..MAX_REPAIRS) {
            val error = try {
                val prefs = create()
                opened = prefs
                // A caller that throws here must not look like a storage failure.
                runCatching { onOpen(prefs) }
                return prefs
            } catch (failure: Exception) {
                failure
            }
            when (CredentialStoreRepair.next(error, attempt)) {
                CredentialRepair.WaitAndRetry -> return null
                CredentialRepair.DeleteFile -> deleteFile()
                CredentialRepair.ReplaceKey -> {
                    replaceMasterKey()
                    deleteFile()
                }
                CredentialRepair.GiveUp -> {
                    closedForGood = true
                    if (fileExists()) losses.record(kind)
                    return null
                }
            }
        }
        closedForGood = true
        return null
    }

    /** Reads through [block], or answers [fallback] when the read is not possible. */
    fun <T> read(fallback: T, block: (SharedPreferences) -> T): T {
        val prefs = openOrNull() ?: return fallback
        return try {
            block(prefs)
        } catch (failure: Exception) {
            repairAfterUse(failure)
            fallback
        }
    }

    /** Writes through [block]. Answers false when nothing was written. */
    fun write(block: SharedPreferences.Editor.() -> Unit): Boolean {
        val prefs = openOrNull() ?: return false
        return try {
            // `commit` rather than `apply`: the caller must learn whether the write landed.
            prefs.edit().apply(block).commit()
        } catch (failure: Exception) {
            repairAfterUse(failure)
            false
        }
    }

    /**
     * One value that will not decrypt means the keyset no longer matches the file. The rest
     * of the file is equally unreadable, so it goes, and the next open builds a new one.
     */
    private fun repairAfterUse(failure: Exception) {
        if (CredentialStoreRepair.next(failure, attempt = 0) != CredentialRepair.DeleteFile) return
        synchronized(lock) { deleteFile() }
    }

    private fun deleteFile() {
        opened = null
        val existed = fileExists()
        runCatching { context.deleteSharedPreferences(fileName) }
        // Report only a real loss. A broken keystore on a first launch destroys nothing,
        // and a message about lost credentials there would be a lie.
        if (existed) losses.record(kind)
    }

    /**
     * Deletes the master key, so the next `MasterKey.Builder` makes a new one.
     *
     * Both files share the default alias, so this also ends the other file. That is correct
     * when the key is the broken part, and it is why this step comes second.
     */
    private fun replaceMasterKey() {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE)
                .apply { load(null) }
                .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }
    }

    private fun fileExists(): Boolean = runCatching {
        File(context.dataDir, "$SHARED_PREFS_DIR/$fileName.xml").exists()
    }.getOrDefault(false)

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val SHARED_PREFS_DIR = "shared_prefs"

        /** A new file, then a new key. The ladder in [CredentialStoreRepair] has no third rung. */
        const val MAX_REPAIRS = 2
    }
}
