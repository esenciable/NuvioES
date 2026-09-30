package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.ext.livetv.data.epg.EpgChannel
import com.nuvio.tv.ext.livetv.data.epg.EpgGuideIndex
import com.nuvio.tv.ext.livetv.data.epg.EpgProgram
import com.nuvio.tv.ext.livetv.data.epg.EpgSnapshot
import com.nuvio.tv.ext.livetv.data.epg.XmlTvGuide
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategory
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategoryId
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import com.nuvio.tv.ext.livetv.domain.model.canBeHidden
import com.nuvio.tv.ext.livetv.domain.model.preferenceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvRowsTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `resolves the programme on air and the one after it`() {
        val rows = LiveTvRows.build(
            channels = listOf(channel()),
            guide = guideOf(
                program("Antes", now - 2 * HOUR, now - HOUR),
                program("Ahora", now - 10 * MINUTE, now + 50 * MINUTE),
                program("Después", now + 50 * MINUTE, now + 2 * HOUR)
            ),
            aliases = emptyMap(),
            favorites = emptySet(),
            nowEpochMs = now
        )

        val row = rows.single()
        assertEquals("Ahora", row.now?.title)
        assertEquals("Después", row.next?.title)
        assertTrue(row.hasGuide)
    }

    @Test
    fun `shows only the next programme when nothing is on air yet`() {
        val rows = LiveTvRows.build(
            channels = listOf(channel()),
            guide = guideOf(program("Después", now + MINUTE, now + HOUR)),
            aliases = emptyMap(),
            favorites = emptySet(),
            nowEpochMs = now
        )

        val row = rows.single()
        assertNull(row.now)
        assertEquals("Después", row.next?.title)
        assertTrue("a next programme still counts as a guide", row.hasGuide)
    }

    @Test
    fun `a channel the guide does not cover has no rows and no guide`() {
        val rows = LiveTvRows.build(
            channels = listOf(channel(name = "Canal Que No Existe")),
            guide = guideOf(program("Ahora", now, now + HOUR)),
            aliases = emptyMap(),
            favorites = emptySet(),
            nowEpochMs = now
        )

        val row = rows.single()
        assertNull(row.now)
        assertNull(row.next)
        assertFalse(row.hasGuide)
    }

    @Test
    fun `marks favourites by stable key, not by position`() {
        val first = channel(id = "c1")
        val second = channel(id = "c2")

        val rows = LiveTvRows.build(
            channels = listOf(first, second),
            guide = EpgSnapshot.EMPTY,
            aliases = emptyMap(),
            favorites = setOf(second.stableKey),
            nowEpochMs = now
        )

        assertFalse(rows[0].isFavorite)
        assertTrue(rows[1].isFavorite)
    }

    @Test
    fun `an explicit alias resolves a channel the name would not`() {
        val rows = LiveTvRows.build(
            channels = listOf(channel(name = "El Trece")),
            guide = guideOf(program("Ahora", now, now + HOUR)),
            aliases = LiveTvRows.normaliseAliases(mapOf("El Trece" to "guide-1")),
            favorites = emptySet(),
            nowEpochMs = now
        )

        assertEquals("Ahora", rows.single().now?.title)
    }

    @Test
    fun `aliases are normalised so capitalisation does not matter`() {
        val normalised = LiveTvRows.normaliseAliases(mapOf("Canal Trece HD" to "guide-1"))

        assertEquals(mapOf("canaltrece" to "guide-1"), normalised)
    }

    @Test
    fun `categories put the two built-ins first and the catalogs in order`() {
        val categories = LiveTvRows.categoriesFor(
            listOf(
                catalog("vivo", "En vivo"),
                catalog("deportes", "Deportes")
            )
        )

        assertEquals(
            listOf(
                LiveTvCategoryId.All,
                LiveTvCategoryId.Favorites,
                LiveTvCategoryId.Addon(ADDON, "vivo"),
                LiveTvCategoryId.Addon(ADDON, "deportes")
            ),
            categories.map { it.id }
        )
        assertEquals("Deportes", categories[3].addonCatalogName)
        assertNull("built-ins carry no addon name", categories[0].addonCatalogName)
    }

    @Test
    fun `an empty hidden set keeps every category visible`() {
        val categories = LiveTvRows.categoriesFor(listOf(catalog("vivo", "En vivo")))

        val visible = LiveTvRows.visibleCategoriesFor(categories, emptySet())

        assertEquals(categories.map { it.id }, visible.map { it.id })
    }

    @Test
    fun `a hidden id removes only that category`() {
        val categories = LiveTvRows.categoriesFor(
            listOf(
                catalog("vivo", "En vivo"),
                catalog("deportes", "Deportes")
            )
        )
        val hidden = LiveTvCategoryId.Addon(ADDON, "deportes").preferenceKey

        val visible = LiveTvRows.visibleCategoriesFor(categories, setOf(hidden))

        assertEquals(
            listOf(
                LiveTvCategoryId.All,
                LiveTvCategoryId.Favorites,
                LiveTvCategoryId.Addon(ADDON, "vivo")
            ),
            visible.map { it.id }
        )
    }

    @Test
    fun `an unknown hidden id is tolerated and hides nothing`() {
        val categories = LiveTvRows.categoriesFor(listOf(catalog("vivo", "En vivo")))

        val visible = LiveTvRows.visibleCategoriesFor(categories, setOf("not-a-category", "addon:"))

        assertEquals(categories.map { it.id }, visible.map { it.id })
    }

    @Test
    fun `All and Favorites cannot be hidden`() {
        val categories = LiveTvRows.categoriesFor(listOf(catalog("vivo", "En vivo")))

        assertFalse(LiveTvCategoryId.All.canBeHidden)
        assertFalse(LiveTvCategoryId.Favorites.canBeHidden)
        assertTrue(LiveTvCategoryId.Addon(ADDON, "vivo").canBeHidden)

        val visible = LiveTvRows.visibleCategoriesFor(
            categories,
            setOf(LiveTvCategoryId.All.preferenceKey, LiveTvCategoryId.Favorites.preferenceKey)
        )

        assertEquals(
            listOf(
                LiveTvCategoryId.All,
                LiveTvCategoryId.Favorites,
                LiveTvCategoryId.Addon(ADDON, "vivo")
            ),
            visible.map { it.id }
        )
    }

    @Test
    fun `a category key is built from identity, never from display text`() {
        val categories = listOf(
            LiveTvCategory(id = LiveTvCategoryId.All, addonCatalogName = "Todos"),
            LiveTvCategory(id = LiveTvCategoryId.Favorites, addonCatalogName = "Favoritos"),
            LiveTvCategory(
                id = LiveTvCategoryId.Addon(ADDON, "vivo"),
                addonCatalogName = "En vivo"
            )
        )

        assertEquals("all", categories[0].id.preferenceKey)
        assertEquals("favorites", categories[1].id.preferenceKey)
        assertEquals("addon:$ADDON|vivo", categories[2].id.preferenceKey)
    }

    @Test
    fun `a programme stops being on air at its stop time`() {
        val programme = com.nuvio.tv.ext.livetv.domain.model.LiveTvProgramme(
            title = "Uno",
            description = null,
            startEpochMs = now,
            stopEpochMs = now + HOUR
        )

        assertTrue(programme.isOnAirAt(now))
        assertTrue(programme.isOnAirAt(now + HOUR - 1))
        assertFalse("the stop instant is exclusive", programme.isOnAirAt(now + HOUR))
    }

    @Test
    fun `progress runs from zero to one and never leaves that range`() {
        val programme = com.nuvio.tv.ext.livetv.domain.model.LiveTvProgramme(
            title = "Uno",
            description = null,
            startEpochMs = now,
            stopEpochMs = now + HOUR
        )

        assertEquals(0f, programme.progressAt(now), 0.001f)
        assertEquals(0.5f, programme.progressAt(now + HOUR / 2), 0.001f)
        assertEquals(1f, programme.progressAt(now + HOUR), 0.001f)
        assertEquals("before it starts it clamps to zero", 0f, programme.progressAt(now - HOUR), 0.001f)
        assertEquals("after it ends it clamps to one", 1f, programme.progressAt(now + 5 * HOUR), 0.001f)
    }

    private fun guideOf(vararg programmes: EpgProgram): EpgSnapshot {
        val guide = XmlTvGuide(
            channels = listOf(EpgChannel("guide-1", listOf("Canal 13"), null)),
            programsByChannelId = mapOf("guide-1" to programmes.toList()),
            totalProgramsParsed = programmes.size,
            programsSkippedOutOfWindow = 0
        )
        return EpgSnapshot(
            guide = guide,
            index = EpgGuideIndex.of(guide.channels),
            loadedAtEpochMs = now,
            sourceIds = listOf("source-1")
        )
    }

    private fun program(title: String, from: Long, to: Long) = EpgProgram(
        channelId = "guide-1",
        title = title,
        description = null,
        startEpochMs = from,
        stopEpochMs = to
    )

    private fun channel(id: String = "c1", name: String = "Canal 13") = LiveTvChannel(
        id = id,
        addonBaseUrl = ADDON,
        addonName = "Addon",
        catalogIds = setOf("vivo"),
        catalogName = "En vivo",
        apiType = "tv",
        name = name,
        logoUrl = null,
        posterUrl = null,
        description = null,
        genres = emptyList()
    )

    private fun catalog(id: String, name: String) = LiveTvCatalog(
        addonBaseUrl = ADDON,
        addonName = "Addon",
        addonId = "addon",
        catalogId = id,
        catalogName = name,
        apiType = "tv"
    )

    private companion object {
        const val ADDON = "https://addon.test/token"
        const val MINUTE = 60L * 1000L
        const val HOUR = 60L * MINUTE
    }
}
