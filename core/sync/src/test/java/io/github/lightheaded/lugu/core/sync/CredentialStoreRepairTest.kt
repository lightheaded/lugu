package io.github.lightheaded.lugu.core.sync

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import org.junit.Test

/**
 * What the app does when encrypted storage refuses to open or to decrypt.
 *
 * These cases decide whether a listener loses a 30-day session, so they are pinned rather
 * than trusted to review. The repair itself needs a device — there is no Android keystore
 * in a unit test — but the decision does not, and the decision is the part that can be
 * wrong in a way nobody notices until an upgrade.
 */
class CredentialStoreRepairTest {

    /**
     * A full disk, or a file that another process holds, is not a broken keyset. Deleting
     * stored tokens over it would sign somebody out for a fault that fixes itself.
     */
    @Test
    fun `a storage error waits and keeps the stored bytes`() {
        assertThat(CredentialStoreRepair.next(IOException("no space left"), attempt = 0))
            .isEqualTo(CredentialRepair.WaitAndRetry)
    }

    /** Waiting stays the answer, however often it happens. Nothing is destroyed on a guess. */
    @Test
    fun `a storage error never escalates to a delete`() {
        (0..4).forEach { attempt ->
            assertThat(CredentialStoreRepair.next(IOException(), attempt))
                .isEqualTo(CredentialRepair.WaitAndRetry)
        }
    }

    /**
     * The escalation ladder. A new file first, because it is the smaller loss and it fixes
     * a corrupt keyset. A new master key second, because it also ends the other file.
     */
    @Test
    fun `a crypto failure deletes the file, then replaces the key, then gives up`() {
        val error = GeneralSecurityException("keyset is not readable")
        assertThat(CredentialStoreRepair.next(error, attempt = 0)).isEqualTo(CredentialRepair.DeleteFile)
        assertThat(CredentialStoreRepair.next(error, attempt = 1)).isEqualTo(CredentialRepair.ReplaceKey)
        assertThat(CredentialStoreRepair.next(error, attempt = 2)).isEqualTo(CredentialRepair.GiveUp)
        assertThat(CredentialStoreRepair.next(error, attempt = 3)).isEqualTo(CredentialRepair.GiveUp)
    }

    /**
     * A lock-screen change or a backup restore invalidates the master key. On a device that
     * arrives as `KeyPermanentlyInvalidatedException`, which extends `InvalidKeyException`.
     * The Android subclass is not on a unit-test classpath, so its parent stands in.
     */
    @Test
    fun `an invalidated key is treated as a crypto failure`() {
        assertThat(CredentialStoreRepair.next(InvalidKeyException(), attempt = 0))
            .isEqualTo(CredentialRepair.DeleteFile)
        assertThat(CredentialStoreRepair.next(KeyStoreException(), attempt = 0))
            .isEqualTo(CredentialRepair.DeleteFile)
    }

    /**
     * `EncryptedSharedPreferences` throws `SecurityException` from every getter when a value
     * will not decrypt. It is unchecked, so nothing in the compiler points at it.
     */
    @Test
    fun `a value that will not decrypt is a crypto failure`() {
        assertThat(CredentialStoreRepair.next(SecurityException("could not decrypt value"), attempt = 0))
            .isEqualTo(CredentialRepair.DeleteFile)
    }

    /**
     * Tink reports a keyset problem as an `IOException` with the real fault underneath.
     * Reading only the outermost type would wait forever on a file that never recovers.
     */
    @Test
    fun `a crypto failure inside a storage error is still a crypto failure`() {
        val wrapped = IOException("keyset", GeneralSecurityException("tag mismatch"))
        assertThat(CredentialStoreRepair.next(wrapped, attempt = 0))
            .isEqualTo(CredentialRepair.DeleteFile)
    }

    /** And the other way round, so the order of the wrapping does not change the answer. */
    @Test
    fun `a storage error inside a crypto failure is a crypto failure`() {
        val wrapped = GeneralSecurityException("keyset", IOException("busy"))
        assertThat(CredentialStoreRepair.next(wrapped, attempt = 0))
            .isEqualTo(CredentialRepair.DeleteFile)
    }

    /**
     * An unfamiliar failure destroys nothing. A wipe on a guess is the one mistake here that
     * cannot be undone, so the app gives up reading rather than gives up the tokens.
     */
    @Test
    fun `an unfamiliar failure gives up without a delete`() {
        listOf(NullPointerException(), IllegalStateException(), RuntimeException("?")).forEach { error ->
            assertThat(CredentialStoreRepair.next(error, attempt = 0)).isEqualTo(CredentialRepair.GiveUp)
        }
    }

    /** A cause chain that points at itself must not hang the caller. */
    @Test
    fun `a self referring cause terminates`() {
        assertThat(CredentialStoreRepair.next(SelfCausedError(), attempt = 0))
            .isEqualTo(CredentialRepair.GiveUp)
    }

    /** Nor must a chain of two errors that name each other. */
    @Test
    fun `a cause cycle terminates`() {
        val first = CyclingError()
        val second = CyclingError()
        first.other = second
        second.other = first
        assertThat(CredentialStoreRepair.next(first, attempt = 0)).isEqualTo(CredentialRepair.GiveUp)
    }

    private class SelfCausedError : RuntimeException() {
        override val cause: Throwable get() = this
    }

    private class CyclingError : RuntimeException() {
        var other: Throwable? = null
        override val cause: Throwable? get() = other
    }
}
