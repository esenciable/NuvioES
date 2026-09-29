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
    val catalogId: String,
    val catalogName: String,
    val name: String,
    val logoUrl: String?,
    val posterUrl: String?,
    val description: String?,
    val genres: List<String>
) {
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
    catalogName: String
): LiveTvChannel = LiveTvChannel(
    id = id,
    addonBaseUrl = addonBaseUrl,
    addonName = addonName,
    catalogId = catalogId,
    catalogName = catalogName,
    name = name,
    logoUrl = logo?.takeIf(String::isNotBlank) ?: poster?.takeIf(String::isNotBlank),
    posterUrl = poster?.takeIf(String::isNotBlank),
    description = description?.takeIf(String::isNotBlank),
    genres = genres
)
