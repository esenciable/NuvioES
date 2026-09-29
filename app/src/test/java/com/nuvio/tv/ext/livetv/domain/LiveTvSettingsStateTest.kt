package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.ext.livetv.domain.model.EpgSource
import com.nuvio.tv.ext.livetv.domain.model.EpgSourceOrigin
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategoryId
import com.nuvio.tv.ext.livetv.domain.model.LiveTvStatus
import com.nuvio.tv.ext.livetv.domain.model.preferenceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The settings state slice is pure: given the catalogs, the discovered guide sources and the three
 * store values, it is the exact [com.nuvio.tv.ext.livetv.domain.model.LiveTvUiState] the settings
 * pane reads -- and nothing more.
 */
class LiveTvSettingsStateTest {

    @Test
    fun `builds the slice with the five fields the pane reads`() {
        val state = LiveTvSettingsState.build(
            catalogs = listOf(catalog("esencial-play-live-todo", "TV · Todo")),
            epgSources = listOf(addonSource(), builtInSource("AR1", "Argentina")),
            hideAdultChannels = false,
            disabledEpgSourceIds = setOf("builtin:AR1"),
            hiddenCategoryIds = setOf("addon:https://addon.test/abc|otro")
        )

        assertEquals(false, state.adultFilterActive)
        assertEquals(
            listOf(
                LiveTvCategoryId.All,
                LiveTvCategoryId.Favorites,
                LiveTvCategoryId.Addon("https://addon.test/abc", "esencial-play-live-todo")
            ),
            state.categories.map { it.id }
        )
        // The pane labels a hideable category with the addon's own catalog name.
        assertEquals("TV · Todo", state.categories.last().addonCatalogName)
        assertEquals(
            listOf("addon:https://addon.test/abc", "builtin:AR1"),
            state.epgSources.map { it.id }
        )
        assertEquals(setOf("builtin:AR1"), state.disabledEpgSourceIds)
        assertEquals(setOf("addon:https://addon.test/abc|otro"), state.hiddenCategoryIds)
    }

    @Test
    fun `leaves everything the pane does not read at the state defaults`() {
        val state = LiveTvSettingsState.build(
            catalogs = listOf(catalog("vivo", "Vivo")),
            epgSources = emptyList(),
            hideAdultChannels = true,
            disabledEpgSourceIds = emptySet(),
            hiddenCategoryIds = emptySet()
        )

        // Building this state must not drag in the channel list or the guide: a Settings pane that
        // loads 1170 channels and parses an XMLTV document to flip three toggles is the mistake the
        // reference fork made.
        assertEquals(LiveTvStatus.READY, state.status)
        assertEquals(emptyList<Any>(), state.channels)
        assertEquals(0, state.totalChannelCount)
        assertEquals(false, state.guideLoaded)
        assertEquals(0, state.guideProgrammeCount)
        assertNull(state.errorMessage)
    }

    @Test
    fun `no catalogs is EMPTY and carries only the two built-in categories`() {
        val state = LiveTvSettingsState.build(
            catalogs = emptyList(),
            epgSources = emptyList(),
            hideAdultChannels = true,
            disabledEpgSourceIds = emptySet(),
            hiddenCategoryIds = emptySet()
        )

        assertEquals(LiveTvStatus.EMPTY, state.status)
        assertEquals(
            listOf(LiveTvCategoryId.All, LiveTvCategoryId.Favorites),
            state.categories.map { it.id }
        )
    }

    @Test
    fun `the hideable key of an addon category is its preference key`() {
        val id = LiveTvCategoryId.Addon("https://addon.test/abc", "vivo")

        assertEquals(id.preferenceKey, LiveTvSettingsState.hideableKey(id))
    }

    @Test
    fun `All and Favorites have no hideable key`() {
        // Without All the list has no default state, and Favorites is our own store rather than a
        // category an addon published. Neither can be hidden, so neither gets a key to store.
        assertNull(LiveTvSettingsState.hideableKey(LiveTvCategoryId.All))
        assertNull(LiveTvSettingsState.hideableKey(LiveTvCategoryId.Favorites))
    }

    private fun catalog(catalogId: String, name: String) = LiveTvCatalog(
        addonBaseUrl = "https://addon.test/abc",
        addonName = "Esencial Play",
        addonId = "addon.test",
        catalogId = catalogId,
        catalogName = name,
        apiType = "tv"
    )

    private fun addonSource() = EpgSource(
        id = "addon:https://addon.test/abc",
        name = "Esencial Play",
        url = "https://addon.test/abc/epg.xml",
        origin = EpgSourceOrigin.FromAddon("https://addon.test/abc")
    )

    private fun builtInSource(tag: String, label: String) = EpgSource(
        id = "builtin:$tag",
        name = label,
        url = "https://epgshare01.online/epgshare01/epg_ripper_$tag.xml.gz",
        origin = EpgSourceOrigin.BuiltIn
    )
}
