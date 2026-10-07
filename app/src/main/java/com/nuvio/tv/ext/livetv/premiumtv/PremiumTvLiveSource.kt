package com.nuvio.tv.ext.livetv.premiumtv

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.ext.livetv.data.premiumtv.M3uEntry
import com.nuvio.tv.ext.livetv.domain.LiveTvCatalog
import com.nuvio.tv.ext.livetv.domain.NativeLiveSource
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import java.security.MessageDigest

/**
 * The identity PremiumTV live wears inside the fork's Live TV: a NATIVE source that is not an addon,
 * rendered by the exact same models the addon catalogs use so the UI treats it like any other live
 * source. It is ONE ENTRY of the [com.nuvio.tv.ext.livetv.data.NativeLiveSources] list injected
 * through DI; nothing downstream names it. Mirrors [com.nuvio.tv.ext.livetv.magis.MagisLiveSource].
 *
 * [BASE_URL] is a sentinel, not a network address: every identity-sensitive structure — channel
 * stable keys, category ids, the catalog selector's pairing of addon to channel — keys off the
 * addon base URL, and a scheme no real addon manifest can ever carry (`premiumtv://`) keeps the
 * native channels from ever colliding with a real one or with the Magis sentinel's. The channel key
 * therefore takes the same shape as the addon key (`BASE_URL|id`), so favourites, focus restoration
 * and deduplication work without a single special case downstream.
 *
 * Unlike Magis there is NOTHING to configure — no crypto, no session, no portal credentials: the
 * source reads a public M3U list, so it is always present and its loader is always configured.
 */
internal object PremiumTvLiveSource : NativeLiveSource {

    override val baseUrl: String get() = BASE_URL

    override val sourceName: String get() = SOURCE_NAME

    override val placeholderAddon: Addon get() = PLACEHOLDER_ADDON

    /**
     * Sentinel "addon base URL" for every PremiumTV native channel and catalog. Never resolved over
     * the network; [ownsChannel] is the only routing decision made with it.
     */
    const val BASE_URL = "premiumtv://native-live"

    /** The display name the source carries in channel rows and category chips. */
    const val SOURCE_NAME = "PremiumTV"

    /** Sentinel addon id, for the same reason as [BASE_URL]. */
    const val ADDON_ID = "premiumtv-native-live"

    /** The catalog apiType a native channel carries. Never sent anywhere: resolution is native. */
    const val API_TYPE = "tv"

    /**
     * Placeholder [Addon] handed to the resolver interface, which takes an addon because the ADDON
     * resolver needs its manifest. The PremiumTV resolver ignores it; the placeholder exists so a
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

    /** Whether [channel] plays through the native PremiumTV source rather than an installed addon. */
    override fun ownsChannel(channel: LiveTvChannel): Boolean = channel.addonBaseUrl == BASE_URL

    /**
     * One M3U group as a Live TV catalog: the category slider chip comes from
     * [LiveTvCatalog.catalogName], and membership filtering is the same
     * `addonBaseUrl + catalogId` pair an addon category uses. The group title is the catalog id
     * itself — the list's only stable group identity, and one the parser guarantees to be present.
     */
    fun catalogFrom(groupTitle: String): LiveTvCatalog = LiveTvCatalog(
        addonBaseUrl = BASE_URL,
        addonName = SOURCE_NAME,
        addonId = ADDON_ID,
        catalogId = groupTitle,
        catalogName = groupTitle,
        apiType = API_TYPE,
    )

    /**
     * One M3U entry as a Live TV channel. [catalog] is the group it was read from; when the same
     * channel appears in several groups the loader unions [LiveTvChannel.catalogIds] exactly like
     * the addon loader and the Magis loader do.
     */
    fun channelFrom(entry: M3uEntry, catalog: LiveTvCatalog): LiveTvChannel = LiveTvChannel(
        id = channelIdFor(entry.url),
        addonBaseUrl = BASE_URL,
        addonName = SOURCE_NAME,
        catalogIds = setOf(catalog.catalogId),
        catalogName = catalog.catalogName,
        apiType = API_TYPE,
        name = entry.name,
        // A channel's visual identity is its logo; the M3U list declares it in `tvg-logo`.
        logoUrl = entry.logoUrl,
        posterUrl = null,
        description = null,
        genres = emptyList(),
    )

    /**
     * The STABLE channel id for one entry: a short SHA-256 hex of the stream URL.
     *
     * The M3U list carries no per-channel code to key on, and a positional or title-based id would
     * not survive the list being reordered or titles being renamed between refreshes. The URL is
     * the one identity the list declares — and the resolver rebuilds its id -> entry index from the
     * CURRENT document on every resolve, so a refreshed list with a changed URL is simply a new
     * channel, while a reordered list keeps every identity intact.
     */
    fun channelIdFor(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return digest.take(ID_BYTES).joinToString("") { "%02x".format(it) }
    }

    private const val ID_BYTES = 8
}
