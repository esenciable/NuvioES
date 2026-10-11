package com.nuvio.tv.ext.livetv.ui

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.ext.livetv.data.LiveTvStore
import com.nuvio.tv.ext.livetv.data.RememberedNativeCatalog
import com.nuvio.tv.ext.livetv.domain.model.preferenceKey
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The settings ViewModel's native wiring end to end: the observed native catalogs arrive from the
 * store (never from a load this ViewModel performs), merge with the addon catalogs, and an empty
 * observation adds nothing. The addon side is empty here, so every group that appears came through
 * the store bridge -- the exact path the native sources can only reach that way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvSettingsViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun `observed native catalogs reach the pane state through the store`() = runTest {
        val native = MutableStateFlow(emptyList<RememberedNativeCatalog>())
        val viewModel = LiveTvSettingsViewModel(addonRepository(), store(native))
        runCurrent()

        // Before the screen has published anything, no group exists: an empty observation adds
        // nothing, and this ViewModel never loads a catalog to fill the gap.
        assertTrue(viewModel.state.value.categoryGroups.isEmpty())

        native.value = listOf(
            RememberedNativeCatalog("Magis", "magis://native-live", "3", "Deportes"),
            RememberedNativeCatalog("PremiumTV", "premiumtv://native-live", "Deportes", "Deportes")
        )
        runCurrent()

        assertEquals(
            listOf("Magis", "PremiumTV"),
            viewModel.state.value.categoryGroups.map { it.provider }
        )
        assertEquals(
            listOf(
                "addon:magis://native-live|3",
                "addon:premiumtv://native-live|Deportes"
            ),
            viewModel.state.value.categoryGroups
                .flatMap { it.categories }
                .map { it.id.preferenceKey }
        )
    }

    @Test
    fun `a catalog the sources stop publishing disappears from the pane`() = runTest {
        val native = MutableStateFlow(
            listOf(
                RememberedNativeCatalog("Magis", "magis://native-live", "3", "Deportes"),
                RememberedNativeCatalog("Magis", "magis://native-live", "7", "Cine")
            )
        )
        val viewModel = LiveTvSettingsViewModel(addonRepository(), store(native))
        runCurrent()
        assertEquals(listOf("Magis"), viewModel.state.value.categoryGroups.map { it.provider })

        native.value = listOf(RememberedNativeCatalog("Magis", "magis://native-live", "3", "Deportes"))
        runCurrent()

        assertEquals(
            listOf(listOf("addon:magis://native-live|3")),
            viewModel.state.value.categoryGroups.map { group -> group.categories.map { it.id.preferenceKey } }
        )
    }

    private fun addonRepository(): AddonRepository = mockk {
        every { getInstalledAddons() } returns flowOf(emptyList())
    }

    private fun store(native: MutableStateFlow<List<RememberedNativeCatalog>>): LiveTvStore = mockk {
        every { hideAdultChannels } returns MutableStateFlow(true)
        every { disabledEpgSourceIds } returns MutableStateFlow(emptySet())
        every { hiddenCategoryIds } returns MutableStateFlow(emptySet())
        every { disabledSports } returns MutableStateFlow(emptySet())
        every { knownSports } returns MutableStateFlow(emptySet())
        every { rememberedNativeCatalogs } returns native
    }
}
