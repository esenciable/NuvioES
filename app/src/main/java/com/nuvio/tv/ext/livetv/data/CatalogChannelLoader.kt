package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.ext.livetv.domain.LiveTvCatalog
import com.nuvio.tv.ext.livetv.domain.LiveTvPageOutcome
import com.nuvio.tv.ext.livetv.domain.LiveTvPaging
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import com.nuvio.tv.ext.livetv.domain.model.toLiveTvChannel
import kotlinx.coroutines.flow.first

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
 */
class CatalogChannelLoader(
    private val catalogRepository: CatalogRepository,
    private val pageSize: Int = DEFAULT_PAGE_SIZE
) : LiveTvChannelLoader {

    override suspend fun load(catalogs: List<LiveTvCatalog>): LiveTvChannelLoad {
        val channels = LinkedHashMap<String, LiveTvChannel>()
        var failedCatalogs = 0

        for (catalog in catalogs) {
            var skip = 0
            var pageIndex = 0

            while (true) {
                val page = fetchPage(catalog, skip)
                if (page == null) {
                    failedCatalogs++
                    break
                }

                val before = channels.size
                page.items.forEach { meta ->
                    val channel = meta.toLiveTvChannel(
                        addonBaseUrl = page.addonBaseUrl,
                        addonName = page.addonName,
                        catalogId = catalog.catalogId,
                        catalogName = catalog.catalogName,
                        apiType = catalog.apiType
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

                if (!catalog.supportsSkip) break

                val keepGoing = LiveTvPaging.shouldRequestAnotherPage(
                    LiveTvPageOutcome(
                        pageIndex = pageIndex,
                        itemsOnPage = page.items.size,
                        newItems = channels.size - before,
                        addonReportsMore = page.hasMore
                    )
                )
                if (!keepGoing) break

                // The addon told us where the next page starts; if that does not move, paging would
                // loop forever on the same page.
                val nextSkip = page.nextSkip
                if (nextSkip <= skip) break

                skip = nextSkip
                pageIndex++
            }
        }

        return LiveTvChannelLoad(channels = channels.values.toList(), failedCatalogs = failedCatalogs)
    }

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
    }
}
