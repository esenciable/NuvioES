package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.ext.livetv.domain.LiveTvCatalog
import com.nuvio.tv.ext.livetv.domain.LiveTvPageOutcome
import com.nuvio.tv.ext.livetv.domain.LiveTvPaging
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import com.nuvio.tv.ext.livetv.domain.model.toLiveTvChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class LiveTvChannelLoad(
    val channels: List<LiveTvChannel>,
    /** Catalogs that could not be read at all. Non-zero is worth surfacing, not swallowing. */
    val failedCatalogs: Int
)

interface LiveTvChannelLoader {
    suspend fun load(catalogs: List<LiveTvCatalog>): LiveTvChannelLoad
}

/**
 * Reads channels out of the selected addon catalogs.
 *
 * All the fetching is upstream's [CatalogRepository], which already builds the URLs, preserves the
 * addon's query string and maps the response. This only decides which catalogs, how far to page, and
 * how to turn items into channels.
 *
 * Deduplication is by [LiveTvChannel.stableKey], so the same channel published by two catalogs of the
 * same addon appears once -- which matters because the addon publishes several overlapping TV catalogs.
 *
 * Paging stops are delegated to [LiveTvPaging], including the rule that a page which produced nothing
 * new ends the walk. That rule is what keeps an addon that ignores `skip` from being asked for the same
 * page fifteen times on every load.
 *
 * ### Catalogs load in PARALLEL, and why the merge is still ordered
 *
 * The owner's addon publishes a TV catalog per portal category -- around thirty of them -- and the
 * first version walked them in a sequential `for`. That was ~40 HTTP roundtrips on one thread, and a
 * ~40-second "Buscando canales..." the owner lived on the device. Catalogs are independent reads, so
 * they load concurrently under a bounded semaphore (the addon already runs an upstream budget; a
 * thundering herd would be paying someone else's rate limit for our impatience), and each catalog's
 * pages stay sequential because paging is inherently ordered.
 *
 * The MERGE walks the batches in the original catalog order. Order is observable -- it decides the
 * channel list's arrangement, which zapping, the drawer and the guide all inherit -- so parallelism
 * may never reorder it.
 */
class CatalogChannelLoader(
    private val catalogRepository: CatalogRepository,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val concurrency: Semaphore = Semaphore(DEFAULT_CONCURRENCY)
) : LiveTvChannelLoader {

    override suspend fun load(catalogs: List<LiveTvCatalog>): LiveTvChannelLoad = coroutineScope {
        // awaitAll preserves the launch order in its RESULT, so the merge below still folds the
        // catalogs in the exact order the sequential version did.
        val batches: List<CatalogBatch> = catalogs
            .map { catalog -> async { concurrency.withPermit { loadCatalog(catalog) } } }
            .awaitAll()

        val channels = LinkedHashMap<String, LiveTvChannel>()
        var failedCatalogs = 0
        for (batch in batches) {
            failedCatalogs += if (batch.failed) 1 else 0
            mergeBatch(batch, channels)
        }
        LiveTvChannelLoad(channels = channels.values.toList(), failedCatalogs = failedCatalogs)
    }

    /** Loads ONE catalog with all its pages; failures collapse to a failed batch, as before. */
    private suspend fun loadCatalog(catalog: LiveTvCatalog): CatalogBatch {
        val pages = mutableListOf<CatalogRow>()
        val seen = HashSet<String>()
        var skip = 0
        var pageIndex = 0
        var failed = false

        while (true) {
            val page = fetchPage(catalog, skip)
            if (page == null) {
                failed = true
                break
            }
            pages += page

            if (!catalog.supportsSkip) break

            // `newItems` counts items this catalog had not served before. The sequential version
            // counted against the GLOBAL map, which silently truncated any catalog whose items were
            // all duplicates of an earlier one -- but counting naively per PAGE would break the rule
            // that protects against an addon ignoring `skip`: the same page served again must read
            // as nothing new and end the walk.
            val newItems = page.items.count { seen.add(it.id) }

            val keepGoing = LiveTvPaging.shouldRequestAnotherPage(
                LiveTvPageOutcome(
                    pageIndex = pageIndex,
                    itemsOnPage = page.items.size,
                    newItems = newItems,
                    addonReportsMore = page.hasMore
                )
            )
            if (!keepGoing) break

            val nextSkip = page.nextSkip
            if (nextSkip <= skip) break

            skip = nextSkip
            pageIndex++
        }

        return CatalogBatch(catalog = catalog, pages = pages, failed = failed)
    }

    /** Folds one catalog's pages into [channels] exactly as the sequential version did. */
    private fun mergeBatch(batch: CatalogBatch, channels: LinkedHashMap<String, LiveTvChannel>) {
        for (page in batch.pages) {
            page.items.forEach { meta ->
                val channel = meta.toLiveTvChannel(
                    addonBaseUrl = page.addonBaseUrl,
                    addonName = page.addonName,
                    catalogId = batch.catalog.catalogId,
                    catalogName = batch.catalog.catalogName,
                    apiType = batch.catalog.apiType
                )
                // A channel belongs to EVERY catalog that published it. `putIfAbsent` alone kept
                // only the first, which emptied every specific category as soon as the addon's
                // "all" catalog was fetched first.
                val existing = channels[channel.stableKey]
                channels[channel.stableKey] = if (existing == null) {
                    channel
                } else {
                    existing.copy(catalogIds = existing.catalogIds + channel.catalogIds)
                }
            }
        }
    }

    /** One catalog's fetched pages, kept in page order so the merge can fold them blind. */
    private data class CatalogBatch(
        val catalog: LiveTvCatalog,
        val pages: List<CatalogRow>,
        val failed: Boolean
    )

    private suspend fun fetchPage(catalog: LiveTvCatalog, skip: Int): CatalogRow? {
        val results = catalogRepository.getCatalog(
            addonBaseUrl = catalog.addonBaseUrl,
            addonId = catalog.addonId,
            addonName = catalog.addonName,
            catalogId = catalog.catalogId,
            catalogName = catalog.catalogName,
            type = catalog.apiType,
            skip = skip,
            skipStep = pageSize,
            extraArgs = emptyMap(),
            supportsSkip = catalog.supportsSkip
        )
        return when (val settled = results.first { it !is NetworkResult.Loading }) {
            is NetworkResult.Success -> settled.data
            is NetworkResult.Error -> null
            // Unreachable: the `first` above already skipped it. Listed so the branch stays exhaustive
            // if a future NetworkResult state is added.
            is NetworkResult.Loading -> null
        }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 100

        /**
         * Parallel catalog reads. Bounded because the addon fronts an upstream budget: eight
         * concurrent catalog pulls fill the list in a fraction of the sequential time without
         * turning the loader into a thundering herd against the owner's own service.
         */
        const val DEFAULT_CONCURRENCY = 8
    }
}
