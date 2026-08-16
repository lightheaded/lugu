package io.github.lightheaded.lugu.playback

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.content.Context

/**
 * The address of a cover, and what the provider refuses.
 *
 * Fetching cannot be tested here — it needs the graph and a server — so what is pinned is the
 * part that has to be right for a car to see anything at all: the shape of the URI, and that
 * nothing but a cover read gets through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoverProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val provider = Robolectric.buildContentProvider(CoverProvider::class.java).create()
        .get()

    /**
     * The authority carries the application id, so a debug and a release build installed side
     * by side serve their own covers instead of one of them answering for both.
     */
    @Test
    fun `the authority belongs to this install`() {
        val uri = CoverProvider.uri(context, "item-1")

        assertThat(uri.scheme).isEqualTo("content")
        assertThat(uri.authority).isEqualTo("${context.packageName}.covers")
    }

    @Test
    fun `the item and the width both travel in the uri`() {
        val uri = CoverProvider.uri(context, "item-1", width = 600)

        assertThat(uri.pathSegments).containsExactly("cover", "item-1").inOrder()
        assertThat(uri.getQueryParameter("width")).isEqualTo("600")
    }

    /**
     * An id is whatever the server made it, and some of them contain characters that mean
     * something in a URI. Going through the builder is what keeps that from breaking the path.
     */
    @Test
    fun `an awkward id survives the round trip`() {
        val uri = CoverProvider.uri(context, "a b/c?d")

        assertThat(uri.pathSegments).containsExactly("cover", "a b/c?d").inOrder()
    }

    @Test
    fun `covers are read-only`() {
        val uri = CoverProvider.uri(context, "item-1")

        val failure = runCatching { provider.openFile(uri, "w") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SecurityException::class.java)
    }

    /** Nothing here is writable, and saying so beats a silent no-op. */
    @Test
    fun `nothing can be written through it`() {
        val uri = CoverProvider.uri(context, "item-1")

        assertThat(runCatching { provider.insert(uri, null) }.exceptionOrNull())
            .isInstanceOf(UnsupportedOperationException::class.java)
        assertThat(runCatching { provider.delete(uri, null, null) }.exceptionOrNull())
            .isInstanceOf(UnsupportedOperationException::class.java)
        assertThat(runCatching { provider.update(uri, null, null, null) }.exceptionOrNull())
            .isInstanceOf(UnsupportedOperationException::class.java)
    }

    /**
     * A path that is not a cover is answered with nothing, rather than being coerced into one.
     * This is the guard that keeps an exported provider from being a general-purpose fetcher.
     */
    @Test
    fun `only a cover path is answered`() {
        val authority = "${context.packageName}.covers"
        val notCovers = listOf(
            "content://$authority/",
            "content://$authority/cover",
            "content://$authority/cover/",
            "content://$authority/cover/item-1/extra",
            "content://$authority/something/item-1",
        )

        notCovers.forEach { address ->
            val uri = Uri.parse(address)
            assertThat(provider.query(uri, null, null, null, null)).isNull()
            assertThat(provider.openFile(uri, "r")).isNull()
        }
    }

    @Test
    fun `a cover query names the file and enumerates nothing`() {
        val cursor = provider.query(CoverProvider.uri(context, "item-1"), null, null, null, null)

        assertThat(cursor).isNotNull()
        cursor!!.use {
            assertThat(it.count).isEqualTo(1)
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(it.getColumnIndexOrThrow("_display_name")))
                .isEqualTo("item-1.img")
        }
    }
}
