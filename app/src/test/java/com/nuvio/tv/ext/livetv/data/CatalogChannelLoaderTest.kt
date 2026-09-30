package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.ext.livetv.domain.LiveTvCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogChannelLoaderTest {

    @Test
    fun `turns catalog items into channels`() = runTest {
        val repository = FakeCatalogRepository { _, _ -> page("vivo", items("c1", "c2")) }

        val result = loader(repository).load(listOf(catalog()))

        assertEquals(listOf("Canal c1", "Canal c2"), result.channels.map { it.name })
        assertEquals(0, result.failedCatalogs)
        assertEquals(listOf("vivo" to 0), repository.calls)
    }

    @Test
    fun `de-duplicates a channel published by two catalogs`() = runTest {
        // The addon publishes several overlapping TV catalogs, so the same channel arrives twice.
        val repository = FakeCatalogRepository { catalogId, _ ->
            page(catalogId, items("c1").toList())
        }

        val result = loader(repository).load(listOf(catalog("vivo"), catalog("deportes")))

        assertEquals(1, result.channels.size)
    }

    @Test
    fun `pages while the addon reports more and makes progress`() = runTest {
        val repository = FakeCatalogRepository { _, skip ->
            when (skip) {
                0 -> page("vivo", items("c1", "c2"), hasMore = true, nextSkip = 2)
                else -> page("vivo", items("c3"), hasMore = false, nextSkip = 3)
            }
        }

        val result = loader(repository).load(listOf(catalog(supportsSkip = true)))

        assertEquals(listOf("Canal c1", "Canal c2", "Canal c3"), result.channels.map { it.name })
        assertEquals(listOf("vivo" to 0, "vivo" to 2), repository.calls)
    }

    @Test
    fun `stops instead of walking an addon that ignores skip`() = runTest {
        // Without the "no new items" rule this would be asked for the same page fifteen times on every
        // single load, and the addon pays for each request upstream.
        val repository = FakeCatalogRepository { catalogId, _ ->
            page(catalogId, items("c1", "c2"), hasMore = true, nextSkip = 999)
        }

        val result = loader(repository).load(listOf(catalog(supportsSkip = true)))

        assertEquals(2, result.channels.size)
        assertEquals("one repeat is enough to notice", 2, repository.calls.size)
    }

    @Test
    fun `does not page a catalog that does not advertise skip`() = runTest {
        val repository = FakeCatalogRepository { catalogId, _ ->
            page(catalogId, items("c1").toList(), hasMore = true, nextSkip = 50)
        }

        val result = loader(repository).load(listOf(catalog(supportsSkip = false)))

        assertEquals(1, result.channels.size)
        assertEquals(listOf("vivo" to 0), repository.calls)
    }

    @Test
    fun `stops at the page cap`() = runTest {
        var counter = 0
        val repository = FakeCatalogRepository { catalogId, skip ->
            page(catalogId, listOf(item("c${counter++}")), hasMore = true, nextSkip = skip + 1)
        }

        val result = loader(repository).load(listOf(catalog(supportsSkip = true)))

        assertEquals(com.nuvio.tv.ext.livetv.domain.LiveTvPaging.MAX_PAGES_PER_CATALOG, repository.calls.size)
    }

    @Test
    fun `a failing catalog is counted and the others still load`() = runTest {
        val repository = FakeCatalogRepository { catalogId, _ ->
            if (catalogId == "roto") null else page(catalogId, items("c1").toList())
        }

        val result = loader(repository).load(listOf(catalog("roto"), catalog("vivo")))

        assertEquals(1, result.failedCatalogs)
        assertEquals(1, result.channels.size)
    }

    @Test
    fun `a channel published by two catalogs belongs to both, and is still one channel`() = runTest {
        // The bug this pins, reported from the device with a screenshot: with `TV · todo` fetched
        // first, every channel carried that catalog's id and the specific categories -- TV · Deportes,
        // Panama and the rest -- matched nothing at all, so the list said "0 channels" while the
        // categories were plainly full. Deduplication kept the first catalog and threw away the rest.
        val repository = FakeCatalogRepository { catalogId, _ -> page(catalogId, items("c1").toList()) }

        val result = loader(repository).load(listOf(catalog("vivo"), catalog("deportes")))

        assertEquals("still one channel", 1, result.channels.size)
        assertEquals(
            "but it belongs to both catalogs",
            setOf("vivo", "deportes"),
            result.channels.single().catalogIds
        )
    }

    private fun loader(repository: FakeCatalogRepository) =
        CatalogChannelLoader(catalogRepository = repository)

    private class FakeCatalogRepository(
        private val respond: (catalogId: String, skip: Int) -> CatalogRow?
    ) : CatalogRepository {

        val calls = mutableListOf<Pair<String, Int>>()

        override fun getCatalog(
            addonBaseUrl: String,
            addonId: String,
            addonName: String,
            catalogId: String,
            catalogName: String,
            type: String,
            skip: Int,
            skipStep: Int,
            extraArgs: Map<String, String>,
            supportsSkip: Boolean,
            posterScreen: com.nuvio.tv.core.poster.CustomPosterScreen
        ): Flow<NetworkResult<CatalogRow>> = flow {
            calls += catalogId to skip
            emit(NetworkResult.Loading)
            val row = respond(catalogId, skip)
            emit(if (row == null) NetworkResult.Error("no se pudo leer") else NetworkResult.Success(row))
        }
    }

    private fun page(
        catalogId: String,
        items: List<MetaPreview>,
        hasMore: Boolean = false,
        nextSkip: Int = items.size
    ) = CatalogRow(
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = ADDON,
        catalogId = catalogId,
        catalogName = catalogId,
        type = ContentType.TV,
        rawType = "tv",
        items = items,
        hasMore = hasMore,
        nextSkip = nextSkip
    )

    private fun items(vararg ids: String): List<MetaPreview> = ids.map { item(it) }

    private fun item(id: String) = MetaPreview(
        id = id,
        type = ContentType.TV,
        name = "Canal $id",
        poster = null,
        posterShape = PosterShape.LANDSCAPE,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private fun catalog(id: String = "vivo", supportsSkip: Boolean = false) = LiveTvCatalog(
        addonBaseUrl = ADDON,
        addonName = "Addon",
        addonId = "addon",
        catalogId = id,
        catalogName = id,
        apiType = "tv",
        supportsSkip = supportsSkip
    )

    private companion object {
        const val ADDON = "https://addon.test/token"
    }
}
