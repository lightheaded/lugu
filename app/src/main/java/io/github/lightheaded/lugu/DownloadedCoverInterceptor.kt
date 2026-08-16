package io.github.lightheaded.lugu

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import io.github.lightheaded.lugu.core.download.CoverId
import io.github.lightheaded.lugu.core.download.CoverStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Serves a downloaded item's cover from the phone, whatever the screen asked for.
 *
 * Every screen builds the same thing for a cover: the server's `/api/items/<id>/cover` URL,
 * handed to Coil. That is right when there is a server to reach and wrong the moment there is
 * not — a book downloaded in full would still show an empty square on a train, because the
 * audio was on the phone and the picture never was.
 *
 * Fixing it here rather than at each call site is deliberate. There are five view models
 * building cover URLs and a sixth place doing it inline; changing what each of them returns
 * would mean a disk check on the main thread in half a dozen composables, and a new one every
 * time a screen is added. One interceptor is the whole app, including screens not written
 * yet, and it needs nothing from them but the URL they were already passing.
 *
 * Anything that is not a cover URL, or is a cover for an item with no download, passes
 * through untouched and loads exactly as it did before.
 */
class DownloadedCoverInterceptor(private val coverStore: CoverStore) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data as? String ?: return chain.proceed()
        val itemId = CoverId.itemIdIn(url) ?: return chain.proceed()

        // Off the main thread, however Coil chose to run this chain: it is only a `stat`, but
        // a scrolling grid asks for one per row and the main thread has better things to do.
        val stored = withContext(Dispatchers.IO) { coverStore.file(itemId) } ?: return chain.proceed()

        return chain.withRequest(request.newBuilder().data(stored).build()).proceed()
    }
}
