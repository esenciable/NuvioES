package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvCatalogSelectorTest {

    @Test
    fun `selects only the catalog types that carry live television`() {
        val addon = addon(
            baseUrl = "https://addon.test/abc",
            catalogs = listOf(
                catalog("filmes", "Peliculas", ContentType.MOVIE),
                catalog("series", "Series", ContentType.SERIES),
                catalog("esencial-play-live-todo", "TV · Todo", ContentType.TV),
                catalog("canais", "Canais", ContentType.CHANNEL)
            )
        )

        val selected = TvCatalogSelector.select(listOf(addon))

        assertEquals(listOf("esencial-play-live-todo", "canais"), selected.map { it.catalogId })
    }

    @Test
    fun `does not guess from names`() {
        // The reference fork matched substrings against catalog ids and addon names, so a movie
        // catalog called "TV Shows" from an addon called "Live TV Addon" was pulled in as a channel
        // source. Declared type only.
        val addon = addon(
            baseUrl = "https://addon.test/abc",
            name = "Live TV Addon",
            catalogs = listOf(
                catalog("tv-shows", "TV Shows", ContentType.MOVIE),
                catalog("ao-vivo", "Ao Vivo", ContentType.SERIES)
            )
        )

        assertTrue(TvCatalogSelector.select(listOf(addon)).isEmpty())
    }

    @Test
    fun `ignores disabled addons`() {
        val disabled = addon(
            baseUrl = "https://addon.test/apagado",
            enabled = false,
            catalogs = listOf(catalog("vivo", "Vivo", ContentType.TV))
        )

        assertTrue(TvCatalogSelector.select(listOf(disabled)).isEmpty())
    }

    @Test
    fun `honours the user's own addon selection`() {
        val channels = addon(
            baseUrl = "https://addon.test/canales",
            catalogs = listOf(catalog("vivo", "Vivo", ContentType.TV))
        )
        val movies = addon(
            baseUrl = "https://addon.test/peliculas",
            catalogs = listOf(catalog("deportes", "Deportes", ContentType.TV))
        )

        val selected = TvCatalogSelector.select(
            addons = listOf(channels, movies),
            enabledAddonUrls = setOf("https://addon.test/peliculas")
        )

        assertEquals(listOf("https://addon.test/peliculas"), selected.map { it.addonBaseUrl })
    }

    @Test
    fun `keeps addon and catalog order`() {
        val first = addon(
            baseUrl = "https://uno.test/t",
            catalogs = listOf(
                catalog("a", "A", ContentType.TV),
                catalog("b", "B", ContentType.CHANNEL)
            )
        )
        val second = addon(
            baseUrl = "https://dos.test/t",
            catalogs = listOf(catalog("c", "C", ContentType.TV))
        )

        val selected = TvCatalogSelector.select(listOf(first, second))

        assertEquals(listOf("a", "b", "c"), selected.map { it.catalogId })
        assertEquals(
            listOf("https://uno.test/t", "https://uno.test/t", "https://dos.test/t"),
            selected.map { it.addonBaseUrl }
        )
    }

    @Test
    fun `keys are per addon, so two addons cannot collide on a catalog id`() {
        val one = addon(
            baseUrl = "https://uno.test/t",
            catalogs = listOf(catalog("vivo", "Vivo", ContentType.TV))
        )
        val two = addon(
            baseUrl = "https://dos.test/t",
            catalogs = listOf(catalog("vivo", "Vivo", ContentType.TV))
        )

        val selected = TvCatalogSelector.select(listOf(one, two))

        assertEquals(2, selected.map { it.stableKey }.distinct().size)
        assertEquals(2, selected.map { it.categoryId }.distinct().size)
    }

    private fun addon(
        baseUrl: String,
        name: String = baseUrl,
        enabled: Boolean = true,
        catalogs: List<CatalogDescriptor>
    ) = Addon(
        id = name,
        name = name,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = catalogs,
        types = listOf(ContentType.TV),
        resources = emptyList(),
        enabled = enabled
    )

    private fun catalog(id: String, name: String, type: ContentType) =
        CatalogDescriptor(type = type, id = id, name = name)
}
