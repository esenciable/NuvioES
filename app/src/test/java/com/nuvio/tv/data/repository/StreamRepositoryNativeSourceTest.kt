package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.debrid.DebridStreamPresentation
import com.nuvio.tv.core.debrid.LocalDebridAvailabilityService
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.LocalScraperResult
import com.nuvio.tv.domain.model.RepositoryType
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.ext.livetv.data.NativeVodSources
import com.nuvio.tv.ext.livetv.domain.NativeVodSource
import com.nuvio.tv.ext.livetv.domain.NativeVodStream
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * The native VOD sources inside the repository stream path: their groups ride the SAME
 * resultChannel as the scrapers', a failing native source is isolated (logged and skipped,
 * scrapers survive), and an unconfigured source is never resolved at all.
 */
class StreamRepositoryNativeSourceTest {

    private class FakeNativeSource(
        override val name: String,
        override val isConfigured: Boolean = true,
        private val gate: CompletableDeferred<List<NativeVodStream>>? = null,
    ) : NativeVodSource {
        var resolveCalls = 0
        override suspend fun resolve(type: String, videoId: String, season: Int?, episode: Int?): List<NativeVodStream> {
            resolveCalls++
            return gate?.await() ?: listOf(
                NativeVodStream(
                    url = "https://cdn.magis.test/vod/MEDIA1_media.ts",
                    headers = mapOf("Content-Auth" to "auth-token"),
                ),
            )
        }
    }

    @Test
    fun `a failing native source does not drop scraper results`() = runBlocking {
        val failingNative = object : NativeVodSource {
            override val name = "Dying VOD"
            override val isConfigured = true
            override suspend fun resolve(type: String, videoId: String, season: Int?, episode: Int?): List<NativeVodStream> {
                throw IllegalStateException("portal exploded")
            }
        }
        val harness = newHarness(
            nativeSources = listOf(failingNative),
            scrapers = listOf(compatibleScraper()),
        )
        coEvery { harness.tmdbService.ensureTmdbId("tt1341338", "movie") } returns "12345"
        coEvery {
            harness.pluginManager.executeScrapersStreaming(any(), any(), any(), any())
        } returns flowOf(compatibleScraper() to listOf(scraperResult()))

        val success = withTimeout(2_000) {
            harness.repository.getStreamsFromAllAddons("movie", "tt1341338", null, null)
                .first { result ->
                    result is NetworkResult.Success && result.data.any { it.addonName == "Plugin" }
                }
        } as NetworkResult.Success

        val names = success.data.map { it.addonName }
        assertTrue("addon group expected, got $names", "Fast Addon" in names)
        assertTrue("scraper group expected, got $names", "Plugin" in names)
    }

    @Test
    fun `native streams arrive as their own group with the CDN headers`() = runBlocking {
        val harness = newHarness(nativeSources = listOf(FakeNativeSource("Magis VOD")))

        val success = withTimeout(2_000) {
            harness.repository.getStreamsFromAllAddons("movie", "tt1341338", null, null)
                .first { result ->
                    result is NetworkResult.Success && result.data.any { it.addonName == "Magis VOD" }
                }
        } as NetworkResult.Success

        val group = success.data.single { it.addonName == "Magis VOD" }
        val stream = group.streams.single()
        assertEquals("https://cdn.magis.test/vod/MEDIA1_media.ts", stream.getStreamUrl())
        assertEquals("auth-token", stream.behaviorHints?.proxyHeaders?.request?.get("Content-Auth"))
    }

    @Test
    fun `native resolution runs parallel to the addons without blocking them`() = runBlocking {
        // The native resolve never returns on its own; the addon must still be emitted.
        val gatedNative = FakeNativeSource("Magis VOD", gate = CompletableDeferred())
        val harness = newHarness(nativeSources = listOf(gatedNative))

        val result = withTimeout(1_000) {
            harness.repository.getStreamsFromAllAddons("movie", "tt1341338", null, null)
                .first { it is NetworkResult.Success }
        }

        val groups = (result as NetworkResult.Success).data
        assertEquals(listOf("Fast Addon"), groups.map { it.addonName })
        assertEquals(1, gatedNative.resolveCalls)
    }

    @Test
    fun `unconfigured native source is never resolved`() = runBlocking {
        val absent = FakeNativeSource("Absent VOD", isConfigured = false)
        val harness = newHarness(nativeSources = listOf(absent))

        val success = withTimeout(2_000) {
            harness.repository.getStreamsFromAllAddons("movie", "tt1341338", null, null)
                .first { it is NetworkResult.Success }
        } as NetworkResult.Success

        assertTrue(success.data.none { it.addonName == "Absent VOD" })
        assertEquals(0, absent.resolveCalls)
    }

    private fun scraperResult() = LocalScraperResult(
        title = "Scraper Stream",
        url = "https://scraper.example/video.mp4",
    )

    private fun newHarness(nativeSources: List<NativeVodSource>, scrapers: List<ScraperInfo> = emptyList()): Harness {
        val addon = compatibleAddon()
        val api = mockk<AddonApi>()
        coEvery { api.getStreams(any()) } returns Response.success(
            StreamResponseDto(
                streams = listOf(StreamDto(name = "Fast Stream", url = "https://stream.example/video.m3u8")),
            ),
        )

        val addonRepository = mockk<AddonRepository>()
        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(addon))

        val pluginManager = mockk<PluginManager>(relaxed = true)
        every { pluginManager.enabledScrapers } returns flowOf(scrapers)
        every { pluginManager.pluginsEnabled } returns flowOf(scrapers.isNotEmpty())
        every { pluginManager.groupStreamsByRepository } returns flowOf(false)
        every { pluginManager.repositories } returns flowOf(emptyList())

        val profileManager = mockk<ProfileManager>(relaxed = true)
        every { profileManager.activeProfileId } returns MutableStateFlow(1)

        val tmdbService = mockk<TmdbService>(relaxed = true)
        val debridSettingsDataStore = mockk<DebridSettingsDataStore>()
        every { debridSettingsDataStore.settings } returns flowOf(DebridSettings())
        val presentation = mockk<DebridStreamPresentation>()
        every { presentation.apply(any(), any<DebridSettings>(), any(), any()) } answers {
            firstArg<List<AddonStreams>>()
        }
        val availability = mockk<LocalDebridAvailabilityService>()
        coEvery { availability.markChecking(any()) } coAnswers { firstArg<List<AddonStreams>>() }
        coEvery { availability.annotateCachedAvailability(any()) } coAnswers { firstArg<List<AddonStreams>>() }

        return Harness(
            repository = StreamRepositoryImpl(
                context = mockk<Context>(relaxed = true),
                api = api,
                addonRepository = addonRepository,
                pluginManager = pluginManager,
                profileManager = profileManager,
                debridSettingsDataStore = debridSettingsDataStore,
                tmdbService = tmdbService,
                debridStreamPresentation = presentation,
                localDebridAvailabilityService = availability,
                nativeVodSources = NativeVodSources(nativeSources),
            ),
            pluginManager = pluginManager,
            tmdbService = tmdbService,
        )
    }

    private fun compatibleAddon(): Addon = Addon(
        id = "fast-addon",
        name = "Fast Addon",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://addon.example",
        catalogs = emptyList(),
        types = emptyList(),
        resources = listOf(
            AddonResource(name = "stream", types = listOf("movie"), idPrefixes = listOf("tt")),
        ),
    )

    private fun compatibleScraper(): ScraperInfo = ScraperInfo(
        id = "plugin",
        name = "Plugin",
        description = "",
        version = "1.0.0",
        filename = "plugin.js",
        supportedTypes = listOf("movie"),
        enabled = true,
        manifestEnabled = true,
        logo = null,
        contentLanguage = emptyList(),
        repositoryId = "repo",
        formats = null,
        type = RepositoryType.NUVIO_JS,
    )

    private data class Harness(
        val repository: StreamRepositoryImpl,
        val pluginManager: PluginManager,
        val tmdbService: TmdbService,
    )
}
