package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddonStreamResolverTest {

    @Test
    fun `returns the first stream that carries a usable url`() {
        runTest {
            val repository = FakeStreamRepository(
                NetworkResult.Success(listOf(stream(url = "https://cdn.test/one.m3u8"), stream(url = "https://cdn.test/two.m3u8")))
            )

            val resolved = AddonStreamResolver(repository).resolve(addon(), channel())

            assertEquals("https://cdn.test/one.m3u8", resolved?.url)
        }
    }

    @Test
    fun `skips a stream whose url is blank and keeps looking`() {
        runTest {
            val repository = FakeStreamRepository(
                NetworkResult.Success(listOf(stream(url = "   "), stream(url = "https://cdn.test/two.m3u8")))
            )

            assertEquals("https://cdn.test/two.m3u8", AddonStreamResolver(repository).resolve(addon(), channel())?.url)
        }
    }

    @Test
    fun `skips a torrent, which is not openable as live television`() {
        // `getStreamUrl` refuses magnets and torrent urls, and a live channel cannot be one.
        runTest {
            val repository = FakeStreamRepository(
                NetworkResult.Success(
                    listOf(
                        stream(url = "magnet:?xt=urn:btih:abc"),
                        stream(url = "https://cdn.test/live.m3u8")
                    )
                )
            )

            assertEquals("https://cdn.test/live.m3u8", AddonStreamResolver(repository).resolve(addon(), channel())?.url)
        }
    }

    @Test
    fun `carries the addon's request headers`() {
        runTest {
            val headers = mapOf("User-Agent" to "Custom", "Referer" to "https://addon.test/")
            val repository = FakeStreamRepository(
                NetworkResult.Success(listOf(stream(url = "https://cdn.test/live.m3u8", headers = headers)))
            )

            assertEquals(headers, AddonStreamResolver(repository).resolve(addon(), channel())?.headers)
        }
    }

    @Test
    fun `an empty header map becomes null rather than empty`() {
        runTest {
            val repository = FakeStreamRepository(
                NetworkResult.Success(listOf(stream(url = "https://cdn.test/live.m3u8", headers = emptyMap())))
            )

            assertNull(AddonStreamResolver(repository).resolve(addon(), channel())?.headers)
        }
    }

    @Test
    fun `returns null when the addon answers with an error`() {
        runTest {
            val repository = FakeStreamRepository(NetworkResult.Error("boom"))

            assertNull(AddonStreamResolver(repository).resolve(addon(), channel()))
        }
    }

    @Test
    fun `returns null when no stream can be opened`() {
        runTest {
            val repository = FakeStreamRepository(NetworkResult.Success(listOf(stream(url = null, externalUrl = null))))

            assertNull(AddonStreamResolver(repository).resolve(addon(), channel()))
        }
    }

    @Test
    fun `asks the addon for the channel's own type and id`() {
        // The stream endpoint is /stream/{type}/{id}.json: sending the wrong type asks the addon for a
        // catalog it does not have.
        runTest {
            val repository = FakeStreamRepository(NetworkResult.Success(emptyList()))

            AddonStreamResolver(repository).resolve(addon(), channel(id = "cyx_123", apiType = "tv"))

            assertEquals(1, repository.calls.size)
            assertEquals("tv", repository.calls.single().second)
            assertEquals("cyx_123", repository.calls.single().third)
        }
    }

    private class FakeStreamRepository(
        private val response: NetworkResult<List<Stream>>
    ) : StreamRepository {

        val calls = mutableListOf<Triple<Addon, String, String>>()

        override fun setLocalPluginSearchPaused(paused: Boolean) = Unit

        override fun getStreamsFromAllAddons(
            type: String,
            videoId: String,
            season: Int?,
            episode: Int?,
            forceRefresh: Boolean
        ): Flow<NetworkResult<List<AddonStreams>>> = flowOf(NetworkResult.Success(emptyList()))

        override suspend fun getStreamsFromAddon(
            addon: Addon,
            type: String,
            videoId: String
        ): NetworkResult<List<Stream>> {
            calls += Triple(addon, type, videoId)
            return response
        }
    }

    private fun addon() = Addon(
        id = "addon",
        name = "Esencial Play",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://addon.test/token",
        catalogs = emptyList(),
        types = listOf(ContentType.TV),
        resources = emptyList()
    )

    private fun channel(id: String = "c1", apiType: String = "tv") = LiveTvChannel(
        id = id,
        addonBaseUrl = "https://addon.test/token",
        addonName = "Esencial Play",
        catalogId = "vivo",
        catalogName = "En vivo",
        apiType = apiType,
        name = "Canal $id",
        logoUrl = null,
        posterUrl = null,
        description = null,
        genres = emptyList()
    )

    private fun stream(
        url: String?,
        externalUrl: String? = null,
        headers: Map<String, String>? = null
    ) = Stream(
        name = "Fuente",
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = externalUrl,
        behaviorHints = if (headers == null) {
            null
        } else {
            StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = ProxyHeaders(request = headers, response = null)
            )
        },
        addonName = "Esencial Play",
        addonLogo = null
    )
}
