package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.ext.livetv.domain.LiveTvCatalog
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import com.nuvio.tv.ext.livetv.magis.MagisLiveCategory
import com.nuvio.tv.ext.livetv.magis.MagisLiveChannel
import com.nuvio.tv.ext.livetv.magis.MagisLiveCatalogApi
import com.nuvio.tv.ext.livetv.magis.MagisLiveClient
import com.nuvio.tv.ext.livetv.magis.MagisLiveSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The catalog side of the native Magis source, expressed in the fork's Live TV models. It is ONE
 * [NativeLiveCatalogLoader] among the injected native sources.
 *
 * The port the loader consumes — [MagisLiveCatalogApi] — is the smallest slice of
 * [MagisLiveClient] the catalog walk needs, so tests can fake the portal without a session, a
 * crypto stack or a network.
 *
 * Shape mirrors [CatalogChannelLoader]: one load produces the catalogs (category slider chips)
 * plus the merged channel list, deduplication is by stable key with a catalogIds union, and a
 * category that could not be read is counted — not swallowed — so the failure is visible without
 * erasing the categories that did answer.
 */
class MagisChannelLoader(
    /**
     * The portal API, or null when the deployment carries no Magis configuration — a build without
     * MAGIS_* values must behave as if the source does not exist, not crash or load.
     */
    private val client: MagisLiveCatalogApi?,
    /** The page size [MagisLiveClient] requests; a shorter page means the category is exhausted. */
    private val pageSize: Int = MagisLiveClient.PAGE_SIZE,
    /** Hard cap of portal pages per category, so a category that always answers full stops. */
    private val maxPagesPerCategory: Int = DEFAULT_MAX_PAGES,
    private val concurrency: Semaphore = Semaphore(DEFAULT_CONCURRENCY),
) : NativeLiveCatalogLoader {

    /** Whether this build carries a usable Magis configuration at all. */
    override val isConfigured: Boolean get() = client != null

    override suspend fun load(): NativeLiveCatalogLoad {
        val api = client ?: return NativeLiveCatalogLoad.EMPTY
        val categories = runCatching { api.categories() }.getOrDefault(emptyList())
        if (categories.isEmpty()) return NativeLiveCatalogLoad.EMPTY

        // Categories are independent reads; the portal client's own rate limit is the real pacing
        // authority, so a bounded concurrency here only removes dead time between calls.
        val batches: List<CategoryBatch> = coroutineScope {
            categories
                .map { category -> async { concurrency.withPermit { loadCategory(api, category) } } }
                .awaitAll()
        }

        val channels = LinkedHashMap<String, LiveTvChannel>()
        val catalogs = mutableListOf<LiveTvCatalog>()
        var failedCategories = 0
        for (batch in batches) {
            // The failed category still publishes its catalog chip — the category slider must stay
            // stable — but contributes no channels, exactly like a failed addon batch upstream.
            catalogs += batch.catalog
            if (batch.channels == null) {
                failedCategories++
                continue
            }
            mergeChannels(batch.channels, batch.catalog, channels)
        }
        return NativeLiveCatalogLoad(
            catalogs = catalogs,
            channels = channels.values.toList(),
            failedCategories = failedCategories,
        )
    }

    /**
     * One category: the catalog it becomes plus every page of channels — null channels when the
     * portal failed mid-walk, so the category is counted without erasing its chip. Cancellation is
     * rethrown, because an abandoned screen must not be read as a portal failure.
     */
    private suspend fun loadCategory(api: MagisLiveCatalogApi, category: MagisLiveCategory): CategoryBatch {
        val catalog = MagisLiveSource.catalogFrom(category)
        val channels: List<MagisLiveChannel>? = try {
            val loaded = mutableListOf<MagisLiveChannel>()
            for (page in 1..maxPagesPerCategory) {
                val fetched = api.channels(category.id, page)
                loaded += fetched
                // The portal sends full pages until the category runs out, so a short page ends
                // the walk without paying for one more call that would come back empty.
                if (fetched.size < pageSize) break
            }
            loaded
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        return CategoryBatch(catalog = catalog, channels = channels)
    }

    /**
     * Folds one category's channels into the merged list exactly like [CatalogChannelLoader]: a
     * channel belongs to EVERY category that published it, or the specific category filters come
     * up empty as soon as the "all" category was read first.
     */
    private fun mergeChannels(
        magisChannels: List<MagisLiveChannel>,
        catalog: LiveTvCatalog,
        channels: LinkedHashMap<String, LiveTvChannel>,
    ) {
        for (magisChannel in magisChannels) {
            if (magisChannel.code.isBlank()) continue
            val channel = MagisLiveSource.channelFrom(magisChannel, catalog)
            val existing = channels[channel.stableKey]
            channels[channel.stableKey] = if (existing == null) {
                channel
            } else {
                existing.copy(catalogIds = existing.catalogIds + channel.catalogIds)
            }
        }
    }

    private data class CategoryBatch(
        val catalog: LiveTvCatalog,
        /** Null when the category's channel reads failed; the catalog still publishes. */
        val channels: List<MagisLiveChannel>?,
    )

    companion object {
        const val DEFAULT_MAX_PAGES = 5

        /** Bounded like [CatalogChannelLoader.DEFAULT_CONCURRENCY]; the portal's own pace dominates. */
        const val DEFAULT_CONCURRENCY = 4
    }
}
