package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.ext.livetv.data.RememberedNativeCatalog
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategoryId
import com.nuvio.tv.ext.livetv.domain.model.LiveTvStatus
import com.nuvio.tv.ext.livetv.domain.model.canBeHidden
import com.nuvio.tv.ext.livetv.domain.model.preferenceKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The settings state's native merge: addon catalogs plus the native catalogs the Live TV screen
 * observed and published through the store. The merge dedupes by the hiding key, native groups come
 * after addon groups, and an empty observation adds nothing.
 */
class LiveTvSettingsStateCategoryGroupsTest {

    @Test
    fun `merges addon and native catalogs into provider groups, addons first`() {
        val state = LiveTvSettingsState.build(
            catalogs = listOf(catalog("esencial-play-live-todo", "TV · Todo")),
            epgSources = emptyList(),
            hideAdultChannels = true,
            disabledEpgSourceIds = emptySet(),
            hiddenCategoryIds = emptySet(),
            rememberedNativeCatalogs = listOf(
                remembered("Magis", "magis://native-live", "3", "Deportes"),
                remembered("PremiumTV", "premiumtv://native-live", "Deportes", "Deportes")
            )
        )

        assertEquals(
            listOf("Esencial Play", "Magis", "PremiumTV"),
            state.categoryGroups.map { it.provider }
        )
        assertEquals(
            listOf(
                LiveTvCategoryId.Addon("https://addon.test/abc", "esencial-play-live-todo"),
                LiveTvCategoryId.Addon("magis://native-live", "3")
            ),
            state.categoryGroups.take(2).flatMap { it.categories }.map { it.id }
        )
        assertEquals(
            listOf("TV · Todo", "Deportes", "Deportes"),
            state.categoryGroups.flatMap { it.categories }.map { it.addonCatalogName }
        )
    }

    @Test
    fun `a group carries only its own provider's categories`() {
        val state = LiveTvSettingsState.build(
            catalogs = listOf(
                catalog("esencial-play-live-todo", "TV · Todo"),
                catalog("esencial-play-sports-live", "Deportes")
            ),
            epgSources = emptyList(),
            hideAdultChannels = true,
            disabledEpgSourceIds = emptySet(),
            hiddenCategoryIds = emptySet(),
            rememberedNativeCatalogs = listOf(
                remembered("Magis", "magis://native-live", "3", "Deportes"),
                remembered("Magis", "magis://native-live", "7", "Cine")
            )
        )

        val magis = state.categoryGroups.last()
        assertEquals("Magis", magis.provider)
        assertEquals(
            listOf(
                LiveTvCategoryId.Addon("magis://native-live", "3"),
                LiveTvCategoryId.Addon("magis://native-live", "7")
            ),
            magis.categories.map { it.id }
        )
    }

    @Test
    fun `an empty native observation adds nothing to the addon groups`() {
        val state = LiveTvSettingsState.build(
            catalogs = listOf(catalog("esencial-play-live-todo", "TV · Todo")),
            epgSources = emptyList(),
            hideAdultChannels = true,
            disabledEpgSourceIds = emptySet(),
            hiddenCategoryIds = emptySet(),
            rememberedNativeCatalogs = emptyList()
        )

        assertEquals(listOf("Esencial Play"), state.categoryGroups.map { it.provider })
        // Before the user has ever opened Live TV, the pane shows exactly what it showed before
        // the grouping existed: the addon categories and nothing extra.
        assertEquals(
            listOf(
                LiveTvCategoryId.All,
                LiveTvCategoryId.Favorites,
                LiveTvCategoryId.Addon("https://addon.test/abc", "esencial-play-live-todo")
            ),
            state.categories.map { it.id }
        )
    }

    @Test
    fun `a native catalog whose hiding key an addon already published is not offered twice`() {
        // Unreachable through the real sources (every native source keys off a scheme no addon
        // manifest can carry), but the merge defends the invariant at the one place it is built.
        val state = LiveTvSettingsState.build(
            catalogs = listOf(catalog("esencial-play-live-todo", "TV · Todo")),
            epgSources = emptyList(),
            hideAdultChannels = true,
            disabledEpgSourceIds = emptySet(),
            hiddenCategoryIds = emptySet(),
            rememberedNativeCatalogs = listOf(
                remembered("Magis", "https://addon.test/abc", "esencial-play-live-todo", "Duplicado")
            )
        )

        assertEquals(listOf("Esencial Play"), state.categoryGroups.map { it.provider })
        assertEquals(
            listOf(
                LiveTvCategoryId.All,
                LiveTvCategoryId.Favorites,
                LiveTvCategoryId.Addon("https://addon.test/abc", "esencial-play-live-todo")
            ),
            state.categories.map { it.id }
        )
    }

    @Test
    fun `the merged hiding key is the same key the screen filters by`() {
        val state = LiveTvSettingsState.build(
            catalogs = emptyList(),
            epgSources = emptyList(),
            hideAdultChannels = true,
            disabledEpgSourceIds = emptySet(),
            hiddenCategoryIds = setOf("addon:magis://native-live|3"),
            rememberedNativeCatalogs = listOf(
                remembered("Magis", "magis://native-live", "3", "Deportes")
            )
        )

        // The pane's visible check (`key !in hiddenCategoryIds`) hides the native row with the very
        // key the screen's slider uses -- that identity is the whole point of the store bridge.
        assertEquals(
            listOf("addon:magis://native-live|3"),
            state.categories
                .filter { it.id.canBeHidden }
                .map { it.id }
                .filter { it.preferenceKey in setOf("addon:magis://native-live|3") }
                .map { it.preferenceKey }
        )
    }

    @Test
    fun `no addon catalogs and nothing observed stays EMPTY`() {
        val state = LiveTvSettingsState.build(
            catalogs = emptyList(),
            epgSources = emptyList(),
            hideAdultChannels = true,
            disabledEpgSourceIds = emptySet(),
            hiddenCategoryIds = emptySet(),
            rememberedNativeCatalogs = emptyList()
        )

        assertEquals(LiveTvStatus.EMPTY, state.status)
        assertEquals(emptyList<Any>(), state.categoryGroups)
    }

    private fun catalog(catalogId: String, name: String) = LiveTvCatalog(
        addonBaseUrl = "https://addon.test/abc",
        addonName = "Esencial Play",
        addonId = "addon.test",
        catalogId = catalogId,
        catalogName = name,
        apiType = "tv"
    )

    private fun remembered(provider: String, baseUrl: String, catalogId: String, label: String) =
        RememberedNativeCatalog(
            provider = provider,
            addonBaseUrl = baseUrl,
            catalogId = catalogId,
            label = label
        )
}
