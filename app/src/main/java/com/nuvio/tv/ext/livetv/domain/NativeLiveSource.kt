package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel

/**
 * The identity a NATIVE live source wears inside the fork's Live TV: a source that is not an addon,
 * whose channels and catalogs ride the exact same models and the single resolution path the addon
 * sources use.
 *
 * [baseUrl] is a sentinel, not a network address: every identity-sensitive structure — channel
 * stable keys, category ids, the catalog selector's pairing of addon to channel — keys off the addon
 * base URL, so each native source carries a scheme no real addon manifest can ever replicate
 * (`magis://`, `premiumtv://`), keeping its channels from ever colliding with a real addon's or with
 * another native source's.
 */
interface NativeLiveSource {

    /** Sentinel "addon base URL" for every channel and catalog of this source. */
    val baseUrl: String

    /** The display name the source carries in channel rows and category chips. */
    val sourceName: String

    /**
     * Placeholder [Addon] handed to the resolver interface, which takes an addon because the ADDON
     * resolver needs its manifest. Native resolvers ignore it; the placeholder exists so a native
     * channel can flow through the ViewModel's one resolution path without an addon lookup that
     * would otherwise fail — a native channel has no installed addon behind it.
     */
    val placeholderAddon: Addon

    /** Whether [channel] plays through this native source rather than an installed addon. */
    fun ownsChannel(channel: LiveTvChannel): Boolean = channel.addonBaseUrl == baseUrl
}
