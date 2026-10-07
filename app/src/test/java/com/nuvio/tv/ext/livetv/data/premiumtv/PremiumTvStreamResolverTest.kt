package com.nuvio.tv.ext.livetv.data.premiumtv

import com.nuvio.tv.ext.livetv.premiumtv.PremiumTvLiveSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private class StubFetcher(var body: String?) : M3uDocumentFetcher {
    override suspend fun fetch(url: String): String? = body
}

/** The resolver under test; [shortenerHosts] is widened so the MockWebServer probe is reachable. */
private fun resolver(
    fetcher: StubFetcher,
    server: MockWebServer,
    client: OkHttpClient = OkHttpClient(),
) = PremiumTvStreamResolver(
    feed = PremiumTvM3uFeed(fetcher = fetcher, url = "http://list.example/IPTVSV.m3u"),
    ioDispatcher = Dispatchers.IO,
    baseClient = client,
    shortenerHosts = setOf(server.hostName),
)

private fun document(vararg entries: String) = entries.joinToString("\n")

private fun entry(
    name: String,
    url: String,
    userAgent: String? = null,
    referrer: String? = null,
) = buildString {
    append("#EXTINF:-1 group-title=\"G\",$name\n")
    if (userAgent != null) append("#EXTVLCOPT:http-user-agent=$userAgent\n")
    if (referrer != null) append("#EXTVLCOPT:http-referrer=$referrer\n")
    append(url)
}

class PremiumTvStreamResolverTest {

    @Test
    fun `resolves the entry's own declared headers over any default`() = runTest {
        // The measured precedence, case 1: 55 entries declare a UA and 52 a referrer (e.g. the TCS
        // channels of El Salvador). Whatever they declare WINS.
        val fetcher = StubFetcher(
            document(
                entry("TCS", "http://stream/tcs.m3u8", userAgent = "TCS/1.0", referrer = "https://teleon.tv/"),
                entry("Otro", "http://stream/otro.m3u8", userAgent = "Solo-UA/1.0"),
            ),
        )
        MockWebServer().use { server ->
            val result = resolver(fetcher, server).resolveAll(PremiumTvLiveSource.channelIdFor("http://stream/tcs.m3u8"))

            assertEquals(1, result.size)
            assertEquals("http://stream/tcs.m3u8", result.single().url)
            assertEquals(mapOf("User-Agent" to "TCS/1.0", "Referer" to "https://teleon.tv/"), result.single().headers)
        }
    }

    @Test
    fun `falls back to the chrome default UA when the entry declares none`() = runTest {
        val url = "http://stream/a.m3u8"
        val fetcher = StubFetcher(document(entry("A", url)))
        val streamResolver = PremiumTvStreamResolver(
            feed = PremiumTvM3uFeed(fetcher = fetcher, url = "http://list.example/IPTVSV.m3u"),
            ioDispatcher = Dispatchers.IO,
        )

        val result = streamResolver.resolveAll(PremiumTvLiveSource.channelIdFor(url))

        assertEquals(mapOf("User-Agent" to CHROME_UA), result.single().headers)
        assertNull(result.single().headers?.get("Referer"))
    }

    @Test
    fun `the samsung referrer applies only to undeclared samsung-family hosts`() = runTest {
        val fetcher = StubFetcher(
            document(
                entry("Stvp", "http://jmp2.uk/stvp-1"),
                entry("Samsung", "http://stream.samsungcloud.tv/stvp-2"),
                entry("Directo", "http://1.2.3.4:8080/live.m3u8"),
                entry("StvpConReferrer", "http://jmp2.uk/stvp-3", referrer = "https://otro.example/"),
            ),
        )

        // Probing disabled: this test pins the HEADER decision only, and no unit test may reach
        // the real jmp2.uk. The default referrer keys off the URL's host, not the shortener set.
        val streamResolver = PremiumTvStreamResolver(
            feed = PremiumTvM3uFeed(fetcher = fetcher, url = "http://list.example/IPTVSV.m3u"),
            ioDispatcher = Dispatchers.IO,
            shortenerHosts = emptySet(),
        )
        val ids = listOf(
            "http://jmp2.uk/stvp-1",
            "http://stream.samsungcloud.tv/stvp-2",
            "http://1.2.3.4:8080/live.m3u8",
            "http://jmp2.uk/stvp-3",
        ).map { PremiumTvLiveSource.channelIdFor(it) }

        val result = ids.flatMap { streamResolver.resolveAll(it) }

        assertEquals(4, result.size)
        // The plugin's global-referrer defect is NOT copied: only the undeclared Samsung-family
        // hosts get https://www.samsung.com/, and a declared referrer beats the default even on them.
        assertEquals(mapOf("User-Agent" to CHROME_UA, "Referer" to "https://www.samsung.com/"), result[0].headers)
        assertEquals(mapOf("User-Agent" to CHROME_UA, "Referer" to "https://www.samsung.com/"), result[1].headers)
        assertEquals(mapOf("User-Agent" to CHROME_UA), result[2].headers)
        assertEquals(mapOf("User-Agent" to CHROME_UA, "Referer" to "https://otro.example/"), result[3].headers)
    }

    @Test
    fun `follows the shortener redirect and carries the resolved url`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/final.m3u8")))
            server.enqueue(MockResponse().setResponseCode(200))
            val shortenerUrl = server.url("/stvp-1").toString()
            val fetcher = StubFetcher(document(entry("Stvp", shortenerUrl)))
            val streamResolver = resolver(fetcher, server)

            val stream = streamResolver.resolve(PremiumTvLiveSource.channelIdFor(shortenerUrl))

            // The probe followed the redirect chain and the stream carries the FINAL url.
            assertEquals(server.url("/final.m3u8").toString(), stream?.url)
            assertEquals(2, server.requestCount)
            val probe = server.takeRequest()
            assertEquals(CHROME_UA, probe.getHeader("User-Agent"))
            // The stream's label is the source's, the same one the Magis streams carry.
            assertEquals(PremiumTvLiveSource.SOURCE_NAME, stream?.name)
        }
    }

    @Test
    fun `the probe carries the entry's own headers`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/final.m3u8")))
            server.enqueue(MockResponse().setResponseCode(200))
            val shortenerUrl = server.url("/tcs").toString()
            val fetcher = StubFetcher(document(entry("TCS", shortenerUrl, userAgent = "TCS/1.0", referrer = "https://teleon.tv/")))

            resolver(fetcher, server).resolve(PremiumTvLiveSource.channelIdFor(shortenerUrl))

            val probe = server.takeRequest()
            assertEquals("TCS/1.0", probe.getHeader("User-Agent"))
            assertEquals("https://teleon.tv/", probe.getHeader("Referer"))
        }
    }

    @Test
    fun `a non-shortener url is never probed and goes to the player untouched`() = runTest {
        MockWebServer().use { server ->
            val directUrl = server.url("/direct.m3u8").toString()
            val fetcher = StubFetcher(document(entry("Directo", directUrl)))
            // The DEFAULT shortener set: the server's host is not a shortener, so nothing probes it.
            val streamResolver = PremiumTvStreamResolver(
                feed = PremiumTvM3uFeed(fetcher = fetcher, url = "http://list.example/IPTVSV.m3u"),
                ioDispatcher = Dispatchers.IO,
            )

            val stream = streamResolver.resolve(PremiumTvLiveSource.channelIdFor(directUrl))

            assertEquals(directUrl, stream?.url)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `a failed probe degrades to the original url`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            val shortenerUrl = server.url("/stvp-9").toString()
            val fetcher = StubFetcher(document(entry("Stvp", shortenerUrl)))

            val stream = resolver(fetcher, server).resolve(PremiumTvLiveSource.channelIdFor(shortenerUrl))

            assertEquals(shortenerUrl, stream?.url)
        }
    }

    @Test
    fun `an unknown id resolves to nothing`() = runTest {
        MockWebServer().use { server ->
            val fetcher = StubFetcher(document(entry("A", server.url("/a.m3u8").toString())))

            val streamResolver = resolver(fetcher, server)
            assertNull(streamResolver.resolve("no-such-id"))
            assertTrue(streamResolver.resolveAll("no-such-id").isEmpty())
            // Not in the document, so nothing was probed either.
            assertEquals(0, server.requestCount)
        }
    }
}
