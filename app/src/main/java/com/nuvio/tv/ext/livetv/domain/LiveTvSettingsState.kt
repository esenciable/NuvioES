package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.ext.livetv.domain.model.EpgSource
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategoryId
import com.nuvio.tv.ext.livetv.domain.model.LiveTvStatus
import com.nuvio.tv.ext.livetv.domain.model.LiveTvUiState
import com.nuvio.tv.ext.livetv.domain.model.canBeHidden
import com.nuvio.tv.ext.livetv.domain.model.preferenceKey

/**
 * The settings pane's slice of [LiveTvUiState], built without the screen behind it.
 *
 * The in-screen pane is fed by [LiveTvViewModel], whose init loads the whole channel catalog and
 * syncs the guide -- a price worth paying once, on the screen that needs them. The entry inside the
 * app Settings reads the same fields from a state built here instead: the catalogs are selected
 * and the guide sources are discovered (both pure, both cheap), the user preferences and the known
 * sports come from the same [com.nuvio.tv.ext.livetv.data.LiveTvStore] flows the screen observes.
 * No channels are loaded, no EPG is fetched or parsed, and no stream is resolved -- the pane only
 * flips toggles. That design is exactly why the sport rows come from the store's `knownSports` and
 * not from this state's matches list: matches is never populated here.
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
        knownSports: List<String> = emptyList()
    ): LiveTvUiState = LiveTvUiState(
        status = if (catalogs.isEmpty()) LiveTvStatus.EMPTY else LiveTvStatus.READY,
        adultFilterActive = hideAdultChannels,
        categories = LiveTvRows.categoriesFor(catalogs),
        epgSources = epgSources,
        disabledEpgSourceIds = disabledEpgSourceIds,
        hiddenCategoryIds = hiddenCategoryIds,
        disabledSports = disabledSports,
        knownSports = knownSports
    )

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
