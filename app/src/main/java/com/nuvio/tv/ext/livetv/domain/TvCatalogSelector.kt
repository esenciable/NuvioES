package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategoryId

/**
 * One addon catalog that publishes live channels.
 *
 * [stableKey] mirrors the channel key convention: it is the pair of stable identifiers, never the
 * display name.
 */
data class LiveTvCatalog(
    val addonBaseUrl: String,
    val addonName: String,
    val addonId: String,
    val catalogId: String,
    val catalogName: String,
    val apiType: String,
    /**
     * Whether the addon advertises `skip`. Paging a catalog that does not support it would fetch the
     * first page over and over under the guise of pagination.
     */
    val supportsSkip: Boolean = false
) {
    val categoryId: LiveTvCategoryId get() = LiveTvCategoryId.Addon(addonBaseUrl, catalogId)

    val stableKey: String get() = "$addonBaseUrl|$catalogId"
}

/**
 * Chooses which catalogs carry live channels.
 *
 * The selection is **declarative**: it matches Stremio's own `type` for linear television. The
 * reference fork instead matched substrings against the catalog id and the addon name, with a list
 * that included Portuguese words (`brazuca`, `sexta`, `canal`, `ao vivo`), so a movie catalog called
 * "TV Shows" was pulled in as a channel source. There is no guessing here: either the addon declares
 * the catalog as `tv` / `channel`, or it is not a TV catalog.
 *
 * [enabledAddonUrls] is the user's own marking of which addons are channel sources. `null` means
 * "not chosen yet", which selects every enabled addon that publishes a TV catalog -- and then the
 * user can narrow it.
 */
object TvCatalogSelector {

    /** Stremio's type names for linear television. */
    val TV_TYPES: Set<ContentType> = setOf(ContentType.TV, ContentType.CHANNEL)

    fun select(
        addons: List<Addon>,
        enabledAddonUrls: Set<String>? = null
    ): List<LiveTvCatalog> = addons.asSequence()
        .filter { it.enabled }
        .filter { enabledAddonUrls == null || it.baseUrl in enabledAddonUrls }
        .flatMap { addon ->
            addon.catalogs.asSequence()
                .filter { it.type in TV_TYPES }
                .map { catalog ->
                    LiveTvCatalog(
                        addonBaseUrl = addon.baseUrl,
                        addonName = addon.displayName,
                        addonId = addon.id,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        apiType = catalog.apiType,
                        supportsSkip = catalog.extraSupported.any { it.equals("skip", ignoreCase = true) }
                    )
                }
        }
        .toList()
}
