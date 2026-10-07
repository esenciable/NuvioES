package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.ext.livetv.domain.LiveTvPlayableStream
import com.nuvio.tv.ext.livetv.domain.LiveTvStreamResolver
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import com.nuvio.tv.ext.livetv.magis.MagisLiveSource
import com.nuvio.tv.ext.livetv.premiumtv.PremiumTvLiveSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingAddonResolver : LiveTvStreamResolver {
    var calls = 0
    override suspend fun resolve(addon: Addon, channel: LiveTvChannel): LiveTvPlayableStream? {
        calls++
        return LiveTvPlayableStream(url = "addon://resolved", name = null, headers = null)
    }

    override suspend fun resolveAll(addon: Addon, channel: LiveTvChannel): List<LiveTvPlayableStream> {
        calls++
        return listOf(LiveTvPlayableStream(url = "addon://resolved", name = null, headers = null))
    }
}

private class FakeNativeResolver : NativeLiveStreamResolver {
    var calls = 0
    override suspend fun resolve(channelId: String): LiveTvPlayableStream? = null
    override suspend fun resolveAll(channelId: String): List<LiveTvPlayableStream> {
        calls++
        return emptyList()
    }
}

private class ConfiguredNativeLoader(override val isConfigured: Boolean = true) : NativeLiveCatalogLoader {
    override suspend fun load(): NativeLiveCatalogLoad = NativeLiveCatalogLoad.EMPTY
}

private fun tvChannel(baseUrl: String, id: String = "c1") = LiveTvChannel(
    id = id,
    addonBaseUrl = baseUrl,
    addonName = baseUrl,
    catalogIds = setOf("1"),
    catalogName = "Todo",
    apiType = "tv",
    name = id,
    logoUrl = null,
    posterUrl = null,
    description = null,
    genres = emptyList(),
)

private fun testAddon(baseUrl: String = "https://addon.example") = Addon(
    id = "addon",
    name = "Addon",
    version = "1.0",
    description = null,
    logo = null,
    baseUrl = baseUrl,
    catalogs = emptyList(),
    types = emptyList(),
    resources = emptyList(),
)

private fun sources(vararg entries: NativeLiveSources.Entry) = NativeLiveSources(entries.toList())

class RoutingLiveTvStreamResolverPremiumTvTest {

    @Test
    fun `an addon channel still routes to the addon resolver with PremiumTV configured`() = runTest {
        val addonResolver = RecordingAddonResolver()
        val premiumTvResolver = FakeNativeResolver()
        val routing = RoutingLiveTvStreamResolver(
            addonResolver = addonResolver,
            native = sources(
                NativeLiveSources.Entry(PremiumTvLiveSource, ConfiguredNativeLoader(), premiumTvResolver),
            ),
        )

        val channel = tvChannel("https://addon.example")
        val resolved = routing.resolve(testAddon(), channel)

        assertEquals("addon://resolved", resolved?.url)
        assertEquals(1, addonResolver.calls)
        // The PremiumTV source is present and configured, and the addon channel never touched it:
        // a sentinel no real addon can carry keeps the two keyspaces apart.
        assertEquals(0, premiumTvResolver.calls)
    }

    @Test
    fun `a native PremiumTV channel routes to its own resolver, not the addon's`() = runTest {
        val addonResolver = RecordingAddonResolver()
        val premiumTvResolver = FakeNativeResolver()
        val routing = RoutingLiveTvStreamResolver(
            addonResolver = addonResolver,
            native = sources(
                NativeLiveSources.Entry(PremiumTvLiveSource, ConfiguredNativeLoader(), premiumTvResolver),
            ),
        )

        val channel = tvChannel(PremiumTvLiveSource.BASE_URL, id = "deadbeef")
        routing.resolveAll(testAddon(), channel)

        assertEquals(0, addonResolver.calls)
        assertEquals(1, premiumTvResolver.calls)
    }

    @Test
    fun `a magis channel still routes to the magis entry beside PremiumTV`() = runTest {
        val magisResolver = FakeNativeResolver()
        val premiumTvResolver = FakeNativeResolver()
        val routing = RoutingLiveTvStreamResolver(
            addonResolver = RecordingAddonResolver(),
            native = sources(
                NativeLiveSources.Entry(MagisLiveSource, ConfiguredNativeLoader(), magisResolver),
                NativeLiveSources.Entry(PremiumTvLiveSource, ConfiguredNativeLoader(), premiumTvResolver),
            ),
        )

        val result = routing.resolveAll(testAddon(), tvChannel(MagisLiveSource.baseUrl, id = "c-1"))

        assertTrue(result.isEmpty())
        assertEquals(1, magisResolver.calls)
        assertEquals(0, premiumTvResolver.calls)
    }
}
