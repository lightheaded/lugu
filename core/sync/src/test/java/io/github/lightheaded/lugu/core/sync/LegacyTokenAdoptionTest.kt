package io.github.lightheaded.lugu.core.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The upgrade path for a stored sign-in, checked without a device.
 *
 * `EncryptedTokenStore` began holding one set of tokens and now holds one set per account.
 * An install made before that change keeps its tokens under the old key, and the first
 * read after the update has to move them. If that move goes wrong, the app update signs
 * the listener out — the failure the store's own KDoc calls worse than the deprecated
 * library it lives with.
 *
 * The storage itself needs an `AndroidKeyStore` Robolectric does not have. The order of
 * the steps does not, and the order is the part that can be wrong.
 */
class LegacyTokenAdoptionTest {

    private var written: String? = null
    private var legacyRemoved = false

    private fun adopt(legacy: String?, writeLands: Boolean): String? =
        LegacyTokenAdoption.adopt(
            legacy = legacy,
            write = { value ->
                if (writeLands) written = value
                writeLands
            },
            removeLegacy = { legacyRemoved = true },
        )

    @Test
    fun `nothing stored under the old key does nothing at all`() {
        val result = adopt(legacy = null, writeLands = true)

        assertThat(result).isNull()
        assertThat(written).isNull()
        // Nothing was moved, so nothing may be deleted. A blind removal here would delete
        // the keys of an install that never had them, which is harmless once and wrong as
        // a rule.
        assertThat(legacyRemoved).isFalse()
    }

    @Test
    fun `a value that moves is written and then the old key goes`() {
        val result = adopt(legacy = "token-abc", writeLands = true)

        assertThat(result).isEqualTo("token-abc")
        assertThat(written).isEqualTo("token-abc")
        assertThat(legacyRemoved).isTrue()
    }

    /**
     * The case this exists for. A write can fail for reasons that have nothing to do with
     * the value — a full disk is the obvious one, and `SecurePrefs.write` answers false
     * rather than throwing for exactly that reason.
     *
     * The old copy has to survive it. Otherwise a single failed write on the first launch
     * after an update costs a thirty-day session, and no later launch can recover it,
     * because the value it would have read is gone.
     */
    @Test
    fun `a write that does not land leaves the old key alone`() {
        val result = adopt(legacy = "token-abc", writeLands = false)

        assertThat(legacyRemoved).isFalse()
        assertThat(written).isNull()
        // And the caller still gets the tokens: they are the sign-in in force, whatever
        // key they happen to be under.
        assertThat(result).isEqualTo("token-abc")
    }

    @Test
    fun `the value is never altered on the way through`() {
        val original = "a value with @@ in it, which is also the key separator"

        val result = adopt(legacy = original, writeLands = true)

        assertThat(result).isEqualTo(original)
        assertThat(written).isEqualTo(original)
    }
}
