package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.ext.livetv.domain.LiveTvCatalog
import com.nuvio.tv.ext.livetv.magis.MagisLiveCatalogApi
import com.nuvio.tv.ext.livetv.magis.MagisLiveCategory
import com.nuvio.tv.ext.livetv.magis.MagisLiveChannel
import com.nuvio.tv.ext.livetv.magis.MagisLiveSource
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How many channels a fake category serves, per page: category id -> the list of pages.
 * Past the end, every page mints DISTINCT codes (prefixed with the page number), so a
 * "full forever" category is one entry whose repeats stay observable through deduplication.
 */
private fun pagesOf(vararg pages: List<String>): (categoryId: Int, page: Int) -> List<MagisLiveChannel> {
    val pageList = pages.map { codes -> codes.map { MagisLiveChannel(code = it, name = "Canal $it", number = 0, logo = "http://logo/$it.png") } }
    return { _, page ->
        pageList.getOrElse(page - 1) { index ->
            pageList.last().map { code -> MagisLiveChannel(code = "p${index + 1}-$code", name = "Canal $code", number = 0, logo = "") }
        }
    }
}

private class FakeCatalogApi(
    val categories: List<MagisLiveCategory> = emptyList(),
    val channelsFor: (categoryId: Int, page: Int) -> List<MagisLiveChannel> = { _, _ -> emptyList() },
    /** Categories whose channel reads throw, simulating a portal failure. */
    val failingCategories: Set<Int> = emptySet(),
) : MagisLiveCatalogApi {

    val calls = mutableListOf<Pair<Int, Int>>()

    override suspend fun categories(): List<MagisLiveCategory> = categories

    override suspend fun channels(categoryId: Int, page: Int): List<MagisLiveChannel> {
        calls += categoryId to page
        if (categoryId in failingCategories) throw IllegalStateException("portal down")
        return channelsFor(categoryId, page)
    }
}

private fun loader(
    api: MagisLiveCatalogApi?,
    pageSize: Int = 2,
    maxPages: Int = 4,
) = MagisChannelLoader(
    client = api,
    pageSize = pageSize,
    maxPagesPerCategory = maxPages,
    concurrency = Semaphore(4),
)

class MagisChannelLoaderTest {

    @Test
    fun `turns categories and channels into native catalogs and channels`() = runTest {
        val api = FakeCatalogApi(
            categories = listOf(MagisLiveCategory(7, "Deportes"), MagisLiveCategory(9, "Películas")),
            channelsFor = { category, _ ->
                listOf(MagisLiveChannel("cyx-RCNHD", "RCN HD", 1, "http://logo/rcn.png"))
            },
        )

        val result = loader(api).load()

        assertEquals(2, result.catalogs.size)
        assertTrue(result.catalogs.all { it.addonBaseUrl == MagisLiveSource.BASE_URL })
        assertTrue(result.catalogs.all { it.addonName == MagisLiveSource.SOURCE_NAME })
        assertEquals(listOf("Deportes", "Películas"), result.catalogs.map { it.catalogName })
        // One channel per category, deduplicated by code: both catalogs published the same code.
        assertEquals(1, result.channels.size)
        val channel = result.channels.single()
        assertEquals("cyx-RCNHD", channel.id)
        assertEquals("RCN HD", channel.name)
        assertEquals("http://logo/rcn.png", channel.logoUrl)
        assertEquals(setOf("7", "9"), channel.catalogIds)
        assertEquals(0, result.failedCategories)
    }

    @Test
    fun `merges a channel that two categories publish`() = runTest {
        // Same shape as the addon catalogs: the portal's categories overlap, and the channel belongs
        // to every one of them or the specific category filters come up empty.
        val api = FakeCatalogApi(
            categories = listOf(MagisLiveCategory(1, "Todo"), MagisLiveCategory(2, "Noticias")),
            channelsFor = { category, _ ->
                if (category == 1) {
                    listOf(MagisLiveChannel("c1", "Uno", 1, ""), MagisLiveChannel("c2", "Dos", 2, ""))
                } else {
                    listOf(MagisLiveChannel("c2", "Dos", 2, ""))
                }
            },
        )

        val result = loader(api).load()

        assertEquals(2, result.channels.size)
        assertEquals(setOf("1", "2"), result.channels.single { it.id == "c2" }.catalogIds)
        assertEquals(setOf("1"), result.channels.single { it.id == "c1" }.catalogIds)
    }

    @Test
    fun `pages a category until a page is not full`() = runTest {
        val api = FakeCatalogApi(
            categories = listOf(MagisLiveCategory(5, "Todo")),
            channelsFor = pagesOf(listOf("a", "b"), listOf("c", "d"), listOf("e")),
        )

        val result = loader(api, pageSize = 2).load()

        assertEquals(listOf("a", "b", "c", "d", "e"), result.channels.map { it.id })
        assertEquals(listOf(5 to 1, 5 to 2, 5 to 3), api.calls)
    }

    @Test
    fun `caps paging at the per-category page budget`() = runTest {
        // A category that always serves a full page would walk forever; the budget ends it.
        val api = FakeCatalogApi(
            categories = listOf(MagisLiveCategory(5, "Todo")),
            channelsFor = pagesOf(listOf("a", "b")),
        )

        val result = loader(api, pageSize = 2, maxPages = 3).load()

        assertEquals(3, api.calls.size)
        assertEquals(6, result.channels.size)
    }

    @Test
    fun `counts a category whose reads fail and keeps the rest`() = runTest {
        val api = FakeCatalogApi(
            categories = listOf(MagisLiveCategory(1, "Viva"), MagisLiveCategory(2, "Muerta")),
            channelsFor = { category, _ ->
                listOf(MagisLiveChannel("c-$category", "Canal $category", 0, ""))
            },
            failingCategories = setOf(2),
        )

        val result = loader(api).load()

        assertEquals(1, result.failedCategories)
        assertEquals(listOf("c-1"), result.channels.map { it.id })
        // The failed category still publishes its catalog chip so the UI keeps a stable category bar;
        // its channel list is simply empty.
        assertEquals(2, result.catalogs.size)
    }

    @Test
    fun `reports unconfigured and loads nothing without a client`() = runTest {
        val unconfigured = loader(null)

        assertFalse(unconfigured.isConfigured)
        assertEquals(MagisChannelLoader.Load.EMPTY, unconfigured.load())
    }

    @Test
    fun `keeps catalog and channel identities stable against the addon key convention`() = runTest {
        // The stable key must be built from the sentinel base URL plus the code, exactly like the
        // addon key, so favourites and focus restoration survive a reload.
        val api = FakeCatalogApi(
            categories = listOf(MagisLiveCategory(1, "Todo")),
            channelsFor = { _, _ -> listOf(MagisLiveChannel("cyx-X", "X", 0, "")) },
        )

        val result = loader(api).load()

        val channel = result.channels.single()
        assertEquals("${MagisLiveSource.BASE_URL}|cyx-X", channel.stableKey)
        val catalog: LiveTvCatalog = result.catalogs.single()
        assertEquals("${MagisLiveSource.BASE_URL}|1", catalog.stableKey)
    }
}
