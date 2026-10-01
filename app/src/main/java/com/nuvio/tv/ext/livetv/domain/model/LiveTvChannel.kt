package com.nuvio.tv.ext.livetv.domain.model

import com.nuvio.tv.domain.model.MetaPreview

/**
 * A live channel, as consolidated from one of the installed addons.
 *
 * [stableKey] is what identity-sensitive UI depends on: list keys, focus restoration and favourites.
 * It is deliberately built from the addon's base URL plus the item id, both of which survive a
 * reload -- the reference fork keyed focus requesters by list *position* instead, so after any filter
 * change a surviving row reused a requester attached to a different, already disposed channel.
 */
data class LiveTvChannel(
    val id: String,
    val addonBaseUrl: String,
    val addonName: String,
    /**
     * EVERY catalog of this addon that publishes this channel, not just the first one seen.
     *
     * This is a set because that is what is true: the addon publishes overlapping catalogs, and a
     * channel that `TV · todo` and `TV · Deportes` both list belongs to both. Keeping only the first
     * one made every specific category come up empty -- with the "all" catalog fetched first, every
     * channel carried its id and `TV · Deportes` matched nothing. Reported from the device with
     * screenshots: the categories existed and the list said "0 channels".
     */
    val catalogIds: Set<String>,
    /**
     * The catalog this channel was first seen in. Display only -- membership is [catalogIds].
     */
    val catalogName: String,
    /**
     * The catalog's declared type, which is what the addon expects in `/stream/{type}/{id}.json`.
     * Carried on the channel because the stream request needs it and looking the catalog up again to
     * find it would be work for nothing.
     */
    val apiType: String,
    val name: String,
    val logoUrl: String?,
    val posterUrl: String?,
    val description: String?,
    val genres: List<String>,
    /**
     * The ISO kickoff instant the addon publishes in the catalog meta's `released` field, when it
     * ships it. Null until the addon deploys that change, which is why every consumer degrades to
     * the no-kickoff look instead of assuming the field is there.
     */
    val kickoffIso: String? = null
) {
    /**
     * Deduplication key: one entry per channel per addon.
     *
     * Deliberately NOT including the catalog. The same channel listed by three catalogs is one
     * channel, and duplicating it would make the list -- and the count -- wrong.
     */
    val stableKey: String get() = liveTvChannelKey(addonBaseUrl, id)
}

fun liveTvChannelKey(addonBaseUrl: String, channelId: String): String = "$addonBaseUrl|$channelId"

/**
 * Maps an addon catalog item to a channel.
 *
 * Takes the addon's identity as plain fields rather than an [com.nuvio.tv.domain.model.Addon], because
 * the catalog repository hands back a `CatalogRow` carrying exactly these values -- going through the
 * full addon record would mean looking it up again for no reason.
 *
 * A channel's visual identity is its logo; `poster` is the fallback because some addons only fill that
 * one in.
 */
fun MetaPreview.toLiveTvChannel(
    addonBaseUrl: String,
    addonName: String,
    catalogId: String,
    catalogName: String,
    apiType: String
): LiveTvChannel = LiveTvChannel(
    id = id,
    addonBaseUrl = addonBaseUrl,
    addonName = addonName,
    catalogIds = setOf(catalogId),
    catalogName = catalogName,
    apiType = apiType,
    name = name,
    logoUrl = logo?.takeIf(String::isNotBlank) ?: poster?.takeIf(String::isNotBlank),
    posterUrl = poster?.takeIf(String::isNotBlank),
    description = description?.takeIf(String::isNotBlank),
    genres = genres,
    kickoffIso = released?.takeIf(String::isNotBlank)
)
