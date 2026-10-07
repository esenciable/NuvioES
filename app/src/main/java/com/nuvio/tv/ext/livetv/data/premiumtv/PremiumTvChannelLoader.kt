package com.nuvio.tv.ext.livetv.data.premiumtv

import com.nuvio.tv.ext.livetv.data.NativeLiveCatalogLoad
import com.nuvio.tv.ext.livetv.data.NativeLiveCatalogLoader
import com.nuvio.tv.ext.livetv.domain.LiveTvCatalog
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import com.nuvio.tv.ext.livetv.premiumtv.PremiumTvLiveSource

/**
 * The catalog side of the native PremiumTV source, expressed in the fork's Live TV models. It is
 * ONE [NativeLiveCatalogLoader] among the injected native sources.
 *
 * Shape mirrors [com.nuvio.tv.ext.livetv.data.MagisChannelLoader]: one load produces the catalogs
 * (one per `group-title` of the list) plus the merged channel list, and deduplication is by stable
 * key with a catalogIds union — a channel listed in two groups belongs to BOTH, or the specific
 * category filters come up empty as soon as a channel's first group is not the one selected. The
 * failure contract rides on the feed's last-known-good retention: a failed fetch never empties the
 * catalog, and it surfaces as [NativeLiveCatalogLoad.failedCategories] — the document IS the unit
 * this source reads, so one failed fetch is one failed read at the source level, not a per-category
 * one.
 *
 * Unlike Magis there is no disabled-config contract to carry: the list is public, so this loader is
 * ALWAYS configured and [isConfigured] is a constant.
 */
class PremiumTvChannelLoader(
    /** The M3U feed, owner of the TTL, the forced refresh and the last-known-good retention. */
    private val feed: PremiumTvM3uFeed,
) : NativeLiveCatalogLoader {

    /** The list is public: this source is always present, like Magis with a valid configuration. */
    override val isConfigured: Boolean get() = true

    override suspend fun load(): NativeLiveCatalogLoad {
        val feedLoad = feed.load()
        if (feedLoad.entries.isEmpty()) {
            // A failed first fetch (or a body that parses to nothing): no channels, no catalogs,
            // and the failure still counted so it is visible without erasing anything.
            return NativeLiveCatalogLoad(failedCategories = feedLoad.failedFetches)
        }

        // Groups in the list's own order; every entry lands somewhere because the parser gives an
        // entry without `group-title` the synthetic fallback group.
        val groups = LinkedHashMap<String, MutableList<M3uEntry>>()
        for (entry in feedLoad.entries) {
            groups.getOrPut(entry.groupTitle) { mutableListOf() } += entry
        }

        val catalogs = mutableListOf<LiveTvCatalog>()
        val channels = LinkedHashMap<String, LiveTvChannel>()
        for ((group, entries) in groups) {
            val catalog = PremiumTvLiveSource.catalogFrom(group)
            catalogs += catalog
            for (entry in entries) {
                val channel = PremiumTvLiveSource.channelFrom(entry, catalog)
                val existing = channels[channel.stableKey]
                channels[channel.stableKey] = if (existing == null) {
                    channel
                } else {
                    existing.copy(catalogIds = existing.catalogIds + channel.catalogIds)
                }
            }
        }
        return NativeLiveCatalogLoad(
            catalogs = catalogs,
            channels = channels.values.toList(),
            failedCategories = feedLoad.failedFetches,
        )
    }
}
