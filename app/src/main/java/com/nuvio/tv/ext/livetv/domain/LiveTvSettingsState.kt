package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.ext.livetv.domain.model.EpgSource
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategory
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategoryGroup
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategoryId
import com.nuvio.tv.ext.livetv.domain.model.LiveTvStatus
import com.nuvio.tv.ext.livetv.domain.model.LiveTvUiState
import com.nuvio.tv.ext.livetv.domain.model.canBeHidden
import com.nuvio.tv.ext.livetv.domain.model.preferenceKey
import com.nuvio.tv.ext.livetv.data.RememberedNativeCatalog

/**
 * The settings pane's slice of [LiveTvUiState], built without the screen behind it.
 *
 * The in-screen pane is fed by [LiveTvViewModel], whose init loads the whole channel catalog and
 * syncs the guide -- a price worth paying once, on the screen that needs them. The entry inside the
 * app Settings reads the same fields from a state built here instead: the catalogs are selected
 * and the guide sources are discovered (both pure, both cheap), the user preferences, the known
 * sports and the native catalogs the screen observed come from the same
 * [com.nuvio.tv.ext.livetv.data.LiveTvStore] flows the screen observes. No channels are loaded, no
 * catalog is walked, no EPG is fetched or parsed, and no stream is resolved -- the pane only flips
 * toggles. That design is exactly why the sport rows come from the store's `knownSports` and the
 * native category rows from `rememberedNativeCatalogs`, never from this state's matches list:
 * matches is never populated here.
 */
object LiveTvSettingsState {

    /**
     * The exact fields [com.nuvio.tv.ext.livetv.ui.LiveTvSettingsPane] reads, with everything else at
     * the state defaults. Status is stated honestly: with no TV catalog there is nothing to configure,
     * and the pane simply shows the built-in categories and the discovered sources.
     */
    fun build(
        catalogs: List<LiveTvCatalog>,
        epgSources: List<EpgSource>,
        hideAdultChannels: Boolean,
        disabledEpgSourceIds: Set<String>,
        hiddenCategoryIds: Set<String>,
        disabledSports: Set<String> = emptySet(),
        knownSports: List<String> = emptyList(),
        rememberedNativeCatalogs: List<RememberedNativeCatalog> = emptyList()
    ): LiveTvUiState {
        val merged = mergedCategories(catalogs, rememberedNativeCatalogs)
        return LiveTvUiState(
            status = if (catalogs.isEmpty() && rememberedNativeCatalogs.isEmpty()) {
                LiveTvStatus.EMPTY
            } else {
                LiveTvStatus.READY
            },
            adultFilterActive = hideAdultChannels,
            categories = merged,
            categoryGroups = categoryGroups(catalogs, rememberedNativeCatalogs),
            epgSources = epgSources,
            disabledEpgSourceIds = disabledEpgSourceIds,
            hiddenCategoryIds = hiddenCategoryIds,
            disabledSports = disabledSports,
            knownSports = knownSports
        )
    }

    /**
     * The addon catalogs plus the native ones the screen observed, deduplicated by the hiding key.
     *
     * Addon categories come first (the pane's original order), the native ones follow, and a key
     * that appears twice joins the list once. The two cannot actually collide -- every native
     * source keys off a sentinel scheme no addon manifest can carry -- but the dedup lives here so
     * the pane's invariant (one toggle per hiding key) holds by construction, not by trust.
     */
    fun mergedCategories(
        catalogs: List<LiveTvCatalog>,
        rememberedNativeCatalogs: List<RememberedNativeCatalog>
    ): List<LiveTvCategory> {
        val seen = mutableSetOf<String>()
        return buildList {
            add(LiveTvCategory(LiveTvCategoryId.All))
            add(LiveTvCategory(LiveTvCategoryId.Favorites))
            catalogs.forEach { catalog ->
                if (seen.add(catalog.categoryId.preferenceKey)) {
                    add(LiveTvCategory(id = catalog.categoryId, addonCatalogName = catalog.catalogName))
                }
            }
            rememberedNativeCatalogs.forEach { remembered ->
                if (seen.add(remembered.categoryId.preferenceKey)) {
                    add(LiveTvCategory(id = remembered.categoryId, addonCatalogName = remembered.label))
                }
            }
        }
    }

    /**
     * The pane's category rows grouped by provider: one group per addon source, then one per native
     * source, each with that provider's own categories in publication order.
     *
     * A provider with no categories produces no group -- `groupBy` only creates keys it saw -- so
     * before the user has ever opened Live TV the native sources contribute nothing and the pane
     * renders no empty native header. The same key dedup as [mergedCategories] applies across the
     * group boundary: a category an addon already published never joins a native group too.
     */
    fun categoryGroups(
        catalogs: List<LiveTvCatalog>,
        rememberedNativeCatalogs: List<RememberedNativeCatalog>
    ): List<LiveTvCategoryGroup> {
        val seen = mutableSetOf<String>()
        return buildList {
            catalogs.groupBy { it.addonName }.forEach { (provider, providerCatalogs) ->
                val categories = providerCatalogs.mapNotNull { catalog ->
                    val category = LiveTvCategory(id = catalog.categoryId, addonCatalogName = catalog.catalogName)
                    if (seen.add(catalog.categoryId.preferenceKey)) category else null
                }
                if (categories.isNotEmpty()) add(LiveTvCategoryGroup(provider, categories))
            }
            rememberedNativeCatalogs.groupBy { it.provider }.forEach { (provider, remembered) ->
                val categories = remembered.mapNotNull { entry ->
                    val category = LiveTvCategory(id = entry.categoryId, addonCatalogName = entry.label)
                    if (seen.add(entry.categoryId.preferenceKey)) category else null
                }
                if (categories.isNotEmpty()) add(LiveTvCategoryGroup(provider, categories))
            }
        }
    }

    /**
     * The key a category is hidden under in the store, or null when it cannot be hidden.
     *
     * `All` and `Favorites` are the two categories that make the list work at all, so asking to hide
     * either is a no-op: the guard lives here, at the one place that turns an id into a store key, so
     * the ViewModel cannot store a key no slider could ever show.
     */
    fun hideableKey(categoryId: LiveTvCategoryId): String? =
        if (categoryId.canBeHidden) categoryId.preferenceKey else null
}
