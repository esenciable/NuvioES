package com.nuvio.tv.ext.livetv.ui

import android.os.SystemClock
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.ext.livetv.data.LiveTvStore
import com.nuvio.tv.ext.livetv.data.NativeLiveCatalogLoad
import com.nuvio.tv.ext.livetv.data.NativeLiveSources
import com.nuvio.tv.ext.livetv.data.epg.EpgFailure
import com.nuvio.tv.ext.livetv.data.epg.EpgFailureReason
import com.nuvio.tv.ext.livetv.data.epg.EpgRepository
import com.nuvio.tv.ext.livetv.data.epg.EpgState
import com.nuvio.tv.ext.livetv.data.epg.EpgSyncResult
import com.nuvio.tv.ext.livetv.domain.model.LiveTvStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The manifest-refresh contract of the Live TV entry points.
 *
 * Upstream's addon manifest cache never re-evaluates its TTL while the process runs (its
 * staleness check only runs when the installed-addons flow re-emits), so an addon that changed its
 * manifest stays invisible until process death. Live TV therefore re-fetches each installed
 * addon's manifest once per explicit refresh or section entry -- and when that refetch fails, the
 * cached manifest keeps rendering: a dead network must not empty the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvAddonManifestRefreshTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private var elapsedMs = 1_000L

    @Before
    fun setUp() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { elapsedMs }
    }

    @After
    fun tearDown() {
        unmockkStatic(SystemClock::class)
    }

    @Test
    fun `a full refresh fetches each installed addon manifest exactly once`() = runTest {
        val addonRepository = addonRepository(fetchedAddon = addon())
        val viewModel = viewModel(addonRepository, catalogRepository = succeedingCatalogs())

        viewModel.refresh()
        advanceUntilIdle()

        coVerify(exactly = 1) { addonRepository.fetchAddon(ADDON_URL) }
        assertEquals(LiveTvStatus.READY, viewModel.state.value.status)
    }

    @Test
    fun `entering Partidos fetches each installed addon manifest exactly once`() = runTest {
        val addonRepository = addonRepository(fetchedAddon = addon())
        val viewModel = viewModel(addonRepository, catalogRepository = succeedingCatalogs(matchRow()))

        viewModel.refreshMatchesOnly()
        advanceUntilIdle()

        coVerify(exactly = 1) { addonRepository.fetchAddon(ADDON_URL) }
        assertEquals(LiveTvStatus.READY, viewModel.state.value.matchesStatus)
    }

    @Test
    fun `a failed refetch keeps the cached catalogs and the screen still renders`() = runTest {
        val addonRepository = addonRepository(fetchedAddon = null)
        val viewModel = viewModel(addonRepository, catalogRepository = succeedingCatalogs())

        viewModel.refresh()
        advanceUntilIdle()

        // The refetch failed, but the addon's cached manifest still carries the TV catalog, so the
        // screen loads its channels through it instead of going empty.
        assertEquals(LiveTvStatus.READY, viewModel.state.value.status)
        assertTrue(viewModel.state.value.channels.isNotEmpty())
    }

    @Test
    fun `a manifest that stopped publishing the matches catalog is honoured on entry`() = runTest {
        val changedAddon = addon().copy(catalogs = emptyList())
        val addonRepository = addonRepository(fetchedAddon = changedAddon)
        val viewModel = viewModel(addonRepository, catalogRepository = succeedingCatalogs(matchRow()))

        viewModel.refreshMatchesOnly()
        advanceUntilIdle()

        // The refreshed manifest no longer declares the sports-live catalog: saying EMPTY is the
        // addon telling the truth, not the stale cached catalog speaking.
        assertEquals(LiveTvStatus.EMPTY, viewModel.state.value.matchesStatus)
    }

    private fun addonRepository(fetchedAddon: Addon?): AddonRepository = mockk {
        every { getInstalledAddons() } returns flowOf(listOf(addon()))
        coEvery { fetchAddon(any()) } answers {
            fetchedAddon?.let { NetworkResult.Success(it) }
                ?: NetworkResult.Error("addon unreachable", 500)
        }
    }

    private fun succeedingCatalogs(row: CatalogRow = channelRow()): CatalogRepository = mockk {
        every { getCatalog(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            flowOf(NetworkResult.Success(row))
    }

    private fun viewModel(
        addonRepository: AddonRepository,
        catalogRepository: CatalogRepository
    ): LiveTvViewModel = LiveTvViewModel(
        addonRepository = addonRepository,
        catalogRepository = catalogRepository,
        epgRepository = mockk {
            every { state } returns MutableStateFlow(EpgState())
            coEvery { sync(any(), any()) } returns
                EpgSyncResult.Failed(EpgFailure("guide", EpgFailureReason.DOWNLOAD_FAILED))
        },
        streamRepository = mockk<StreamRepository>(),
        liveTvStore = store(),
        nativeSources = mockk<NativeLiveSources> {
            coEvery { load() } returns NativeLiveCatalogLoad.EMPTY
        }
    )

    private fun addon(): Addon = Addon(
        id = "esencial-play",
        name = "Esencial Play",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = ADDON_URL,
        catalogs = listOf(
            CatalogDescriptor(
                type = ContentType.TV,
                id = "esencial-play-sports-live",
                name = "Deportes en vivo"
            )
        ),
        types = listOf(ContentType.TV),
        resources = emptyList()
    )

    /** A real channel: no `rb_` id, so [LiveTvPartition] keeps it in the channel list. */
    private fun channelRow(): CatalogRow = row(
        itemId = "esencial_tv_1",
        name = "Canal 7",
        genres = listOf("General")
    )

    /** A live match: the `rb_` id is what [LiveTvPartition] moves into the matches grid. */
    private fun matchRow(): CatalogRow = row(
        itemId = "rb_123_futbol",
        name = "Boca vs River",
        genres = listOf("Futbol"),
        released = "2026-07-20T18:00:00Z"
    )

    private fun row(
        itemId: String,
        name: String,
        genres: List<String>,
        released: String? = null
    ): CatalogRow = CatalogRow(
        addonId = "esencial-play",
        addonName = "Esencial Play",
        addonBaseUrl = ADDON_URL,
        catalogId = "esencial-play-sports-live",
        catalogName = "Deportes en vivo",
        type = ContentType.TV,
        items = listOf(
            MetaPreview(
                id = itemId,
                type = ContentType.TV,
                name = name,
                poster = null,
                posterShape = PosterShape.LANDSCAPE,
                background = null,
                logo = null,
                description = null,
                releaseInfo = null,
                imdbRating = null,
                genres = genres,
                released = released
            )
        ),
        hasMore = false
    )

    private fun store(): LiveTvStore = mockk {
        every { hideAdultChannels } returns MutableStateFlow(true)
        every { disabledEpgSourceIds } returns MutableStateFlow(emptySet())
        every { hiddenCategoryIds } returns MutableStateFlow(emptySet())
        every { disabledSports } returns MutableStateFlow(emptySet())
        every { favorites } returns MutableStateFlow(emptySet())
        coEvery { rememberSports(any()) } just runs
    }

    private companion object {
        const val ADDON_URL = "https://esencial.example.com"
    }
}
