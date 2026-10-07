package com.nuvio.tv.ext.livetv.data.premiumtv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeFetcher(var body: String?) : M3uDocumentFetcher {
    var calls = 0
    override suspend fun fetch(url: String): String? {
        calls++
        return body
    }
}

private val BODY = """
    #EXTM3U
    #EXTINF:-1 tvg-logo="http://l/a.png" group-title="Deportes",A
    http://stream/a.m3u8
    #EXTINF:-1 tvg-logo="http://l/b.png" group-title="Noticias",B
    http://stream/b.m3u8
""".trimIndent()

private fun feed(fetcher: FakeFetcher, clock: () -> Long, ttlMs: Long = 30 * 60_000L) =
    PremiumTvM3uFeed(
        fetcher = fetcher,
        url = "http://list.example/IPTVSV.m3u",
        clock = clock,
        ttlMs = ttlMs,
    )

class PremiumTvM3uFeedTest {

    @Test
    fun `fetches and parses on first load`() = runTest {
        val fetcher = FakeFetcher(BODY)

        val load = feed(fetcher, clock = { 0L }).load()

        assertEquals(0, load.failedFetches)
        assertEquals(listOf("A", "B"), load.entries.map { it.name })
        assertEquals(1, fetcher.calls)
    }

    @Test
    fun `serves the cached list inside the TTL without re-fetching`() = runTest {
        var now = 0L
        val fetcher = FakeFetcher(BODY)
        val premiumTv = feed(fetcher, clock = { now })
        premiumTv.load()

        now += PremiumTvM3uFeed.DEFAULT_TTL_MS - 1
        val load = premiumTv.load()

        assertEquals(listOf("A", "B"), load.entries.map { it.name })
        assertEquals(1, fetcher.calls)
    }

    @Test
    fun `re-fetches once the TTL has elapsed`() = runTest {
        var now = 0L
        val fetcher = FakeFetcher(BODY)
        val premiumTv = feed(fetcher, clock = { now })
        premiumTv.load()

        now += PremiumTvM3uFeed.DEFAULT_TTL_MS
        premiumTv.load()

        assertEquals(2, fetcher.calls)
    }

    @Test
    fun `a forced load re-consults the list inside the TTL window`() = runTest {
        var now = 0L
        val fetcher = FakeFetcher(BODY)
        val premiumTv = feed(fetcher, clock = { now })
        premiumTv.load()

        // The manual refresh of the Live TV screen re-consults even inside the TTL window.
        premiumTv.load(force = true)

        assertEquals(2, fetcher.calls)
    }

    @Test
    fun `a failed fetch keeps the last-known-good list and reports the failure as a count`() = runTest {
        var now = 0L
        val fetcher = FakeFetcher(BODY)
        val premiumTv = feed(fetcher, clock = { now })
        premiumTv.load()

        fetcher.body = null
        now += PremiumTvM3uFeed.DEFAULT_TTL_MS
        val load = premiumTv.load()

        // The defect of the reference plugin is NOT inherited: a dead list never wipes the channels
        // the user is already watching. The failure surfaces as a count instead.
        assertEquals(listOf("A", "B"), load.entries.map { it.name })
        assertEquals(1, load.failedFetches)
    }

    @Test
    fun `a failed first fetch yields an empty list with the failure counted`() = runTest {
        val fetcher = FakeFetcher(null)

        val load = feed(fetcher, clock = { 0L }).load()

        assertTrue(load.entries.isEmpty())
        assertEquals(1, load.failedFetches)
    }

    @Test
    fun `a body that parses to nothing never replaces a good list`() = runTest {
        var now = 0L
        val fetcher = FakeFetcher(BODY)
        val premiumTv = feed(fetcher, clock = { now })
        premiumTv.load()

        // HTTP 200 with a body that yields no channels is the same silent-empty outcome wearing a
        // success code: it is treated as a failed fetch.
        fetcher.body = "#EXTM3U\n\n"
        now += PremiumTvM3uFeed.DEFAULT_TTL_MS
        val load = premiumTv.load()

        assertEquals(listOf("A", "B"), load.entries.map { it.name })
        assertEquals(1, load.failedFetches)
    }
}

class OkHttpM3uFetcherTest {

    @Test
    fun `sends the chrome user agent and returns the body on 200`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(BODY))

            val body = OkHttpM3uFetcher(OkHttpClient(), Dispatchers.IO).fetch(server.url("/IPTVSV.m3u").toString())

            assertEquals(BODY, body)
            val request = server.takeRequest()
            val userAgent = request.getHeader("User-Agent")
            assertTrue(userAgent != null && userAgent.startsWith("Mozilla/5.0"))
            assertTrue(userAgent!!.contains("Chrome/120"))
        }
    }

    @Test
    fun `returns null on a non-2xx answer`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))

            val body = OkHttpM3uFetcher(OkHttpClient(), Dispatchers.IO).fetch(server.url("/gone.m3u").toString())

            assertNull(body)
        }
    }
}
