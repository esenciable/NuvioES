package com.nuvio.tv.ext.livetv.data.premiumtv

import com.nuvio.tv.ext.livetv.premiumtv.PremiumTvLiveSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class LoaderFakeFetcher(var body: String?) : M3uDocumentFetcher {
    override suspend fun fetch(url: String): String? = body
}

private fun feed(fetcher: LoaderFakeFetcher) = PremiumTvM3uFeed(
    fetcher = fetcher,
    url = "http://list.example/IPTVSV.m3u",
)

private fun loader(body: String?) = PremiumTvChannelLoader(feed = feed(LoaderFakeFetcher(body)))

// Each entry is ONE block (#EXTINF + URL) so the reorder test can permute whole entries without
// breaking the EXTINF↔URL adjacency.
private val ENTRY_TCS = """
    #EXTINF:-1 tvg-logo="http://logo/tcs.png" group-title="El Salvador",TCS HD
    http://stream/tcs.m3u8
""".trimIndent()

private val ENTRY_ESPN = """
    #EXTINF:-1 tvg-logo="http://logo/espn.png" group-title="Deportes",ESPN
    http://stream/espn.m3u8
""".trimIndent()

private val ENTRY_ESPN_2 = """
    #EXTINF:-1 group-title="Deportes",ESPN 2
    http://stream/espn2.m3u8
""".trimIndent()

private val ENTRY_SIN_GRUPO = """
    #EXTINF:-1 tvg-logo="http://logo/sv.png",Sin grupo
    http://stream/sv.m3u8
""".trimIndent()

private val GROUPED_BODY = (listOf(ENTRY_TCS, ENTRY_ESPN, ENTRY_ESPN_2, ENTRY_SIN_GRUPO) + "#EXTM3U")
    .joinToString("\n")

/** Feed with an injectable clock, so a test can walk past the TTL like a session does. */
private fun timedFeed(fetcher: LoaderFakeFetcher, now: () -> Long) = PremiumTvM3uFeed(
    fetcher = fetcher,
    url = "http://list.example/IPTVSV.m3u",
    clock = now,
)

class PremiumTvChannelLoaderTest {

    @Test
    fun `is always configured`() {
        assertTrue(loader(GROUPED_BODY).isConfigured)
    }

    @Test
    fun `turns one group into one catalog and maps the entry fields`() = runTest {
        val result = loader(GROUPED_BODY).load()

        assertEquals(0, result.failedCategories)
        assertEquals(
            listOf("El Salvador", "Deportes", "Sin categoría"),
            result.catalogs.map { it.catalogName },
        )
        assertTrue(result.catalogs.all { it.addonBaseUrl == PremiumTvLiveSource.BASE_URL })
        assertTrue(result.catalogs.all { it.addonId == PremiumTvLiveSource.ADDON_ID })
        assertTrue(result.catalogs.all { it.apiType == PremiumTvLiveSource.API_TYPE })
        assertEquals(listOf("Deportes"), result.catalogs.map { it.catalogName }.filter { it == "Deportes" })

        val tcs = result.channels.single { it.name == "TCS HD" }
        assertEquals(PremiumTvLiveSource.BASE_URL, tcs.addonBaseUrl)
        assertEquals(PremiumTvLiveSource.SOURCE_NAME, tcs.addonName)
        assertEquals(PremiumTvLiveSource.API_TYPE, tcs.apiType)
        assertEquals("http://logo/tcs.png", tcs.logoUrl)
        assertEquals(setOf("El Salvador"), tcs.catalogIds)
        assertEquals("El Salvador", tcs.catalogName)
        // The stable id is the hash of the stream URL, and the channel key is the same
        // `baseUrl|id` shape every addon channel uses.
        assertEquals(PremiumTvLiveSource.channelIdFor("http://stream/tcs.m3u8"), tcs.id)
        assertEquals(
            "${PremiumTvLiveSource.BASE_URL}|${tcs.id}",
            tcs.stableKey,
        )
    }

    @Test
    fun `an entry without group-title lands in the synthetic fallback group`() = runTest {
        val result = loader(GROUPED_BODY).load()

        val withoutGroup = result.channels.single { it.name == "Sin grupo" }
        assertEquals(setOf("Sin categoría"), withoutGroup.catalogIds)
        assertTrue(result.catalogs.any { it.catalogName == "Sin categoría" })
    }

    @Test
    fun `a group with one channel still publishes its catalog`() = runTest {
        val body = """
            #EXTM3U
            #EXTINF:-1 group-title="Solitario",Único
            http://stream/unico.m3u8
        """.trimIndent()

        val result = loader(body).load()

        assertEquals(listOf("Solitario"), result.catalogs.map { it.catalogName })
        assertEquals(1, result.channels.size)
    }

    @Test
    fun `a channel listed in two groups belongs to both with the catalogIds union`() = runTest {
        // Same contract as the Magis and addon loaders: one channel, EVERY group that publishes it.
        // A different URL in another group is a DIFFERENT channel, never merged away.
        val body = """
            #EXTM3U
            #EXTINF:-1 group-title="Todo",Uno
            http://stream/uno.m3u8
            #EXTINF:-1 group-title="Noticias",Uno
            http://stream/uno.m3u8
            #EXTINF:-1 group-title="Noticias",Dos
            http://stream/dos.m3u8
        """.trimIndent()

        val result = loader(body).load()

        assertEquals(2, result.channels.size)
        val uno = result.channels.single { it.name == "Uno" }
        assertEquals(setOf("Todo", "Noticias"), uno.catalogIds)
        assertEquals(setOf("Noticias"), result.channels.single { it.name == "Dos" }.catalogIds)
    }

    @Test
    fun `ids are stable when the document is reordered between loads`() = runTest {
        val fetcher = LoaderFakeFetcher(GROUPED_BODY)
        val premiumTvFeed = feed(fetcher)

        val first = PremiumTvChannelLoader(premiumTvFeed).load()
        // The list re-fetched with its entries in another order — the identities must not move.
        fetcher.body = listOf(ENTRY_ESPN_2, ENTRY_SIN_GRUPO, ENTRY_TCS, ENTRY_ESPN, "#EXTM3U")
            .joinToString("\n")
        premiumTvFeed.load(force = true)
        val second = PremiumTvChannelLoader(premiumTvFeed).load()

        assertEquals(
            first.channels.associate { it.stableKey to it.name },
            second.channels.associate { it.stableKey to it.name },
        )
    }

    @Test
    fun `an empty document yields an empty load`() = runTest {
        val result = loader("#EXTM3U\n").load()

        assertTrue(result.catalogs.isEmpty())
        assertTrue(result.channels.isEmpty())
        // A body that parses to nothing is the silent-empty outcome the feed guards against, so the
        // feed counts it as a failed fetch and the loader surfaces it.
        assertEquals(1, result.failedCategories)
    }

    @Test
    fun `a failed fetch with last-known-good keeps the channels and counts one failure`() = runTest {
        var now = 0L
        val fetcher = LoaderFakeFetcher(GROUPED_BODY)
        val premiumTvFeed = timedFeed(fetcher) { now }
        val loader = PremiumTvChannelLoader(premiumTvFeed)
        loader.load()

        // The document IS the unit this source reads, so one failed fetch is one failed read
        // counted at the source level — while the last-known-good channels stay visible. The
        // failure only surfaces when the feed actually re-consults, i.e. past the TTL.
        fetcher.body = null
        now += PremiumTvM3uFeed.DEFAULT_TTL_MS
        val result = loader.load()

        assertEquals(4, result.channels.size)
        assertEquals(3, result.catalogs.size)
        assertEquals(1, result.failedCategories)
    }

    @Test
    fun `a failed first fetch yields an empty load with the failure counted`() = runTest {
        val result = loader(null).load()

        assertTrue(result.catalogs.isEmpty())
        assertTrue(result.channels.isEmpty())
        assertEquals(1, result.failedCategories)
    }
}
