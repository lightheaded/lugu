package io.github.lightheaded.lugu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers are kept on disk, and this says so rather than assuming it.
 *
 * Upstream app#907 is a library that re-fetches every cover on every scroll, which feels
 * slow in exactly the way people describe and costs data on a metered connection. lugu
 * configures a disk cache explicitly — but "Coil caches by default" was the reasoning
 * recorded in the backlog, and a default is not a decision anybody can point at. Deleting
 * the `diskCache` block would leave every existing screen working and every cover
 * re-fetched, which is a change nothing else here would notice.
 *
 * The loader is asked for through [SingletonImageLoader], which is the same instance every
 * `AsyncImage` in the app resolves, so this is the loader that actually draws covers rather
 * than one built for the test.
 *
 * The method names here are underscored rather than the backticked sentences the JVM suites
 * use. A name with spaces in it needs DEX version 040, which needs minSdk 30; lugu's minSdk
 * is 26, so the test APK will not dex with them.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class CoverCacheTest {

    @Test
    fun the_image_loader_keeps_covers_on_disk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val diskCache = SingletonImageLoader.get(context).diskCache

        assertThat(diskCache).isNotNull()
        // Its own directory rather than the shared image cache, so a cover cannot be
        // evicted by something else the app happens to load.
        assertThat(diskCache!!.directory.name).isEqualTo("covers")
        assertThat(diskCache.maxSize).isAtLeast(64L * 1024 * 1024)
    }
}
