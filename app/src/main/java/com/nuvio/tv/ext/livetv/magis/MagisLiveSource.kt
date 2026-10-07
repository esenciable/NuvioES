package com.nuvio.tv.ext.livetv.magis

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.ext.livetv.domain.LiveTvCatalog
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel

/**
 * The identity Magis live wears inside the fork's Live TV: a NATIVE source that is not an addon,
 * rendered by the exact same models the addon catalogs use so the UI treats it like any other live
 * source.
 *
 * [BASE_URL] is a sentinel, not a network address: every identity-sensitive structure — channel
 * stable keys, category ids, the catalog selector's pairing of addon to channel — keys off the
 * addon base URL, and a scheme that no real addon manifest can ever carry (`magis://`) keeps the
 * native channels from ever colliding with a real one. The channel key therefore takes the same
 * shape as the addon key (`BASE_URL|code`), so favourites, focus restoration and deduplication
 * work without a single special case downstream.
 */
internal object MagisLiveSource {

    /**
     * Sentinel "addon base URL" for every Magis native channel and catalog. Never resolved over
     * the network; [ownsChannel] is the only routing decision made with it.
     */
    const val BASE_URL = "magis://native-live"

    /** The display name the source carries in channel rows and category chips. */
    const val SOURCE_NAME = "Magis"

    /** Sentinel addon id, for the same reason as [BASE_URL]. */
    const val ADDON_ID = "magis-native-live"

    /** The catalog apiType a native channel carries. Never sent anywhere: resolution is native. */
    const val API_TYPE = "tv"

    /**
     * Placeholder [Addon] handed to the resolver interface, which takes an addon because the ADDON
     * resolver needs its manifest. The Magis resolver ignores it; the placeholder exists so a
     * native channel can flow through the ViewModel's one resolution path without an addon lookup
     * that would otherwise fail — a native channel has no installed addon behind it.
     */
    val PLACEHOLDER_ADDON: Addon = Addon(
        id = ADDON_ID,
        name = SOURCE_NAME,
        version = "1.0",
        description = null,
        logo = null,
        baseUrl = BASE_URL,
        catalogs = emptyList(),
        types = emptyList(),
        resources = emptyList(),
    )

    /** Whether [channel] plays through the native Magis client rather than an installed addon. */
    fun ownsChannel(channel: LiveTvChannel): Boolean = channel.addonBaseUrl == BASE_URL

    /**
     * One portal category as a Live TV catalog: the category slider chip comes from
     * [LiveTvCatalog.catalogName], and membership filtering is the same
     * `addonBaseUrl + catalogId` pair an addon category uses.
     */
    fun catalogFrom(category: MagisLiveCategory): LiveTvCatalog = LiveTvCatalog(
        addonBaseUrl = BASE_URL,
        addonName = SOURCE_NAME,
        addonId = ADDON_ID,
        catalogId = category.id.toString(),
        catalogName = category.name,
        apiType = API_TYPE,
    )

    /**
     * One portal channel as a Live TV channel. [catalog] is the category it was read from; when
     * the same channel appears in several categories the loader unions [LiveTvChannel.catalogIds]
     * exactly like the addon loader does.
     */
    fun channelFrom(channel: MagisLiveChannel, catalog: LiveTvCatalog): LiveTvChannel = LiveTvChannel(
        id = channel.code,
        addonBaseUrl = BASE_URL,
        addonName = SOURCE_NAME,
        catalogIds = setOf(catalog.catalogId),
        catalogName = catalog.catalogName,
        apiType = API_TYPE,
        name = channel.name,
        // The portal delivers live art as posterUrl (see [MagisLiveChannel]); a channel's visual
        // identity is its logo, so the portal's poster field lands in the logo slot.
        logoUrl = channel.logo.takeIf(String::isNotBlank),
        posterUrl = null,
        description = null,
        genres = emptyList(),
    )
}
