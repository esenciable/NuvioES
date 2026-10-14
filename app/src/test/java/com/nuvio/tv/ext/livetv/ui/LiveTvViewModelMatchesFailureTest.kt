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
import com.nuvio.tv.ext.livetv.data.NativeLiveSources
import com.nuvio.tv.ext.livetv.data.epg.EpgRepository
import com.nuvio.tv.ext.livetv.domain.model.LiveTvStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The Partidos fast path's failure contract: a sports read that FAILED (or answered empty while the
 * sports catalog IS present) must surface as [LiveTvStatus.ERROR] -- never as the "no live matches"
 * empty state -- and it must NOT stamp the freshness clock, so re-entering the section retries
 * instead of waiting out the TTL with a screen that never loaded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvViewModelMatchesFailureTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private var elapsedMs = 1_000L
    private lateinit var catalogRepository: CatalogRepository

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
    fun `a failed sports read is an error, not the empty state`() = runTest {
        val viewModel = viewModel(
            catalogRepository = mockk {
                every { getCatalog(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                    flowOf(NetworkResult.Error("addon unreachable", 500))
            }
        )

        viewModel.refreshMatchesOnly()
        advanceUntilIdle()

        assertEquals(LiveTvStatus.ERROR, viewModel.state.value.matchesStatus)
    }

    @Test
    fun `a failed sports read does not stamp the clock, so re-entry retries`() = runTest {
        catalogRepository = mockk {
            every { getCatalog(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                flowOf(NetworkResult.Error("addon unreachable", 500))
        }
        val viewModel = viewModel(catalogRepository)

        viewModel.refreshMatchesOnly()
        advanceUntilIdle()

        // Re-entering Partidos must re-read the catalog: the failed read published no timestamp,
        // so the TTL gate has nothing to honor.
        viewModel.refreshMatchesOnly()
        advanceUntilIdle()

        verify(exactly = 2) {
            catalogRepository.getCatalog(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        assertEquals(LiveTvStatus.ERROR, viewModel.state.value.matchesStatus)
    }

    @Test
    fun `a successful read still stamps the clock, so re-entry inside the TTL does not reload`() = runTest {
        catalogRepository = mockk {
            every { getCatalog(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                flowOf(NetworkResult.Success(successRow()))
        }
        val viewModel = viewModel(catalogRepository)

        viewModel.refreshMatchesOnly()
        advanceUntilIdle()

        assertEquals(LiveTvStatus.READY, viewModel.state.value.matchesStatus)

        // No time passes, so the published list is still fresh: the fast path's whole point.
        viewModel.refreshMatchesOnly()
        advanceUntilIdle()

        verify(exactly = 1) {
            catalogRepository.getCatalog(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    private fun viewModel(catalogRepository: CatalogRepository): LiveTvViewModel =
        LiveTvViewModel(
            addonRepository = mockk {
                every { getInstalledAddons() } returns flowOf(listOf(addon()))
            },
            catalogRepository = catalogRepository,
            epgRepository = mockk<EpgRepository>(),
            streamRepository = mockk<StreamRepository>(),
            liveTvStore = store(),
            nativeSources = mockk<NativeLiveSources>()
        )

    private fun addon(): Addon = Addon(
        id = "esencial-play",
        name = "Esencial Play",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://esencial.example.com",
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

    private fun successRow(): CatalogRow = CatalogRow(
        addonId = "esencial-play",
        addonName = "Esencial Play",
        addonBaseUrl = "https://esencial.example.com",
        catalogId = "esencial-play-sports-live",
        catalogName = "Deportes en vivo",
        type = ContentType.TV,
        items = listOf(
            MetaPreview(
                id = "rb_123_futbol",
                type = ContentType.TV,
                name = "Boca vs River",
                poster = null,
                posterShape = PosterShape.LANDSCAPE,
                background = null,
                logo = null,
                description = null,
                releaseInfo = null,
                imdbRating = null,
                genres = listOf("Futbol"),
                released = "2026-07-20T18:00:00Z"
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
}
