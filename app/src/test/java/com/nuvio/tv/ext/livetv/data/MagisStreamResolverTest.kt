package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.ext.livetv.domain.LiveTvPlayableStream
import com.nuvio.tv.ext.livetv.domain.LiveTvStreamResolver
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import com.nuvio.tv.ext.livetv.magis.MagisChannelCdn
import com.nuvio.tv.ext.livetv.magis.MagisChannelSession
import com.nuvio.tv.ext.livetv.magis.MagisLivePlaybackApi
import com.nuvio.tv.ext.livetv.magis.MagisLiveSource
import com.nuvio.tv.ext.livetv.magis.MagisResult
import com.nuvio.tv.domain.model.Addon
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records which authBase each header signing was asked for, so per-CDN signing is observable. */
private class FakePlaybackApi(
    val result: MagisResult<MagisChannelSession> =
        MagisResult.PortalError("test", "no resolve configured"),
) : MagisLivePlaybackApi {

    val signedAuthBases = mutableListOf<String>()

    override suspend fun resolveDetailed(channelCode: String): MagisResult<MagisChannelSession> = result

    override fun playlistUrl(cdn: MagisChannelSession): String =
        "http://${cdn.host}/live/${cdn.playCode}.m3u8"

    override fun signedHeaders(cdn: MagisChannelSession): Map<String, String> {
        signedAuthBases += cdn.authBase
        return mapOf("Content-Auth" to cdn.authBase)
    }
}

private fun session(vararg cdns: MagisChannelCdn) = MagisChannelSession(
    host = cdns.first().host,
    authBase = cdns.first().authBase,
    license = "license-1",
    playCode = "cyx-2EF7E10E40C1ac19D6A9F3ED4CD2",
    ttlSeconds = 14400,
    cdns = cdns.toList(),
)

private fun addonResolverRecorder() = object : LiveTvStreamResolver {
    val asked = mutableListOf<String>()
    override suspend fun resolve(addon: Addon, channel: LiveTvChannel): LiveTvPlayableStream? {
        asked += channel.id
        return LiveTvPlayableStream("http://addon/${channel.id}", "addon", null)
    }

    override suspend fun resolveAll(addon: Addon, channel: LiveTvChannel): List<LiveTvPlayableStream> {
        asked += channel.id
        return listOf(LiveTvPlayableStream("http://addon/${channel.id}", "addon", null))
    }
}

private fun magisChannel(code: String = "cyx-RCNHD") = LiveTvChannel(
    id = code,
    addonBaseUrl = MagisLiveSource.BASE_URL,
    addonName = MagisLiveSource.SOURCE_NAME,
    catalogIds = setOf("1"),
    catalogName = "Todo",
    apiType = "tv",
    name = "RCN HD",
    logoUrl = null,
    posterUrl = null,
    description = null,
    genres = emptyList(),
)

private fun placeholderAddon() = MagisLiveSource.PLACEHOLDER_ADDON

class MagisStreamResolverTest {

    @Test
    fun `one stream per cdn in portal order, signed per cdn`() = runTest {
        val api = FakePlaybackApi(
            result = MagisResult.Ok(
                session(
                    MagisChannelCdn("cdn-a.example", "sign_type=cfl&token=AAAA1111BBBB2222CCCC3333DDDD4444"),
                    MagisChannelCdn("cdn-b.example", "sign_type=cfl&token=EEEE1111BBBB2222CCCC3333DDDD4444"),
                ),
            ),
        )

        val streams = MagisStreamResolver(api).resolveAll("cyx-RCNHD")

        // Portal order is the fallback order: the first entry is what plays, the rest feed the
        // source picker and auto-advance.
        assertEquals(2, streams.size)
        assertEquals("http://cdn-a.example/live/cyx-2EF7E10E40C1ac19D6A9F3ED4CD2.m3u8", streams[0].url)
        assertEquals("http://cdn-b.example/live/cyx-2EF7E10E40C1ac19D6A9F3ED4CD2.m3u8", streams[1].url)
        // Each stream is signed against ITS OWN authBase: signing one CDN's token against another's
        // host is exactly the 401 failure mode the portal produces.
        assertEquals(
            listOf("sign_type=cfl&token=AAAA1111BBBB2222CCCC3333DDDD4444", "sign_type=cfl&token=EEEE1111BBBB2222CCCC3333DDDD4444"),
            api.signedAuthBases,
        )
        assertEquals("sign_type=cfl&token=AAAA1111BBBB2222CCCC3333DDDD4444", streams[0].headers?.get("Content-Auth"))
        assertTrue(streams.all { it.name != null })
    }

    @Test
    fun `resolves nothing when the portal rejects`() = runTest {
        val api = FakePlaybackApi(result = MagisResult.PortalError("portal100024", "session dead"))
        val resolver = MagisStreamResolver(api)

        assertTrue(resolver.resolveAll("cyx-RCNHD").isEmpty())
        assertNull(resolver.resolve("cyx-RCNHD"))
    }

    @Test
    fun `resolves nothing when there is no client`() = runTest {
        val resolver = MagisStreamResolver(null)

        assertTrue(resolver.resolveAll("cyx-RCNHD").isEmpty())
        assertNull(resolver.resolve("cyx-RCNHD"))
    }
}

class RoutingLiveTvStreamResolverTest {

    @Test
    fun `routes native channels to the magis resolver and never asks the addon side`() = runTest {
        val addon = addonResolverRecorder()
        val api = FakePlaybackApi(
            result = MagisResult.Ok(session(MagisChannelCdn("cdn-a.example", "sign_type=cfl&token=AAAA1111BBBB2222CCCC3333DDDD4444"))),
        )
        val resolver = RoutingLiveTvStreamResolver(addon, MagisStreamResolver(api))

        val stream = resolver.resolve(placeholderAddon(), magisChannel())

        assertEquals("http://cdn-a.example/live/cyx-2EF7E10E40C1ac19D6A9F3ED4CD2.m3u8", stream?.url)
        assertTrue(addon.asked.isEmpty())
    }

    @Test
    fun `routes addon channels to the addon resolver`() = runTest {
        val addon = addonResolverRecorder()
        val api = FakePlaybackApi()
        val resolver = RoutingLiveTvStreamResolver(addon, MagisStreamResolver(api))

        val addonChannel = magisChannel().copy(addonBaseUrl = "https://addon.example", id = "tv-1")
        val sources = resolver.resolveAll(placeholderAddon(), addonChannel)

        assertEquals(listOf("http://addon/tv-1"), sources.map { it.url })
        assertEquals(listOf("tv-1"), addon.asked)
        assertTrue(api.signedAuthBases.isEmpty())
    }
}
