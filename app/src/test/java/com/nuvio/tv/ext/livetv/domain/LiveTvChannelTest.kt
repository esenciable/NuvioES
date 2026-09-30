package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategory
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategoryId
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import com.nuvio.tv.ext.livetv.domain.model.inCategory
import com.nuvio.tv.ext.livetv.domain.model.toLiveTvChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvChannelTest {

    @Test
    fun `prefers the logo and falls back to the poster`() {
        val withLogo = meta(id = "1", name = "Uno", logo = "https://log.test/1.png", poster = "https://pos.test/1.jpg")
        val onlyPoster = meta(id = "2", name = "Dos", poster = "https://pos.test/2.jpg")

        assertEquals("https://log.test/1.png", withLogo.toChannel().logoUrl)
        assertEquals("https://pos.test/2.jpg", onlyPoster.toChannel().logoUrl)
    }

    @Test
    fun `treats blank urls as absent`() {
        val blank = meta(id = "1", name = "Uno", logo = "   ", poster = "")

        val channel = blank.toChannel()

        assertNull(channel.logoUrl)
        assertNull(channel.posterUrl)
    }

    @Test
    fun `keys a channel by addon and id, not by position`() {
        // The reference fork keyed focus requesters by list INDEX while items were keyed by channel,
        // so after any filter a surviving row reused a requester attached to a disposed channel.
        val a = channel(id = "c1", addonBaseUrl = "https://uno.test/t")
        val b = channel(id = "c1", addonBaseUrl = "https://dos.test/t")
        val sameAsA = channel(id = "c1", addonBaseUrl = "https://uno.test/t")

        assertEquals(a.stableKey, sameAsA.stableKey)
        assertTrue("two addons must not share a key", a.stableKey != b.stableKey)
        assertEquals("https://uno.test/t|c1", a.stableKey)
    }

    @Test
    fun `category identity does not depend on the display name`() {
        // Regression guard. The reference fork stored the literal label "Favoritos" as the category
        // VALUE and compared against "todos" / "favoritos" / "favorites" in three separate places, so
        // translating the chip silently broke favourites and made the category show every channel.
        // Here the id carries no text at all.
        val asSpanish = LiveTvCategory(LiveTvCategoryId.Addon(ADDON, CATALOG), "TV en vivo")
        val asEnglish = LiveTvCategory(LiveTvCategoryId.Addon(ADDON, CATALOG), "Live TV")
        val subject = channel(id = "c1")

        assertEquals(asSpanish.id, asEnglish.id)
        assertTrue(asSpanish.matches(subject) { false })
        assertTrue(asEnglish.matches(subject) { false })
    }

    @Test
    fun `an addon category matches only channels from that catalog of that addon`() {
        val category = LiveTvCategory(LiveTvCategoryId.Addon(ADDON, CATALOG))
        val sameAddonSameCatalog = channel(id = "c1", catalogId = CATALOG)
        val sameAddonOtherCatalog = channel(id = "c2", catalogId = "otro")
        val otherAddon = channel(id = "c3", addonBaseUrl = "https://dos.test/t", catalogId = CATALOG)

        assertTrue(category.matches(sameAddonSameCatalog) { false })
        assertTrue(!category.matches(sameAddonOtherCatalog) { false })
        assertTrue(!category.matches(otherAddon) { false })
    }

    @Test
    fun `all keeps everything and favorites keep only what is marked`() {
        val channels = listOf(channel(id = "c1"), channel(id = "c2"), channel(id = "c3"))

        assertEquals(
            listOf("c1", "c2", "c3"),
            channels.inCategory(LiveTvCategory(LiveTvCategoryId.All)) { false }.map { it.id }
        )
        assertEquals(
            listOf("c1", "c3"),
            channels.inCategory(LiveTvCategory(LiveTvCategoryId.Favorites)) { it.id != "c2" }.map { it.id }
        )
    }

    private fun meta(
        id: String,
        name: String,
        logo: String? = null,
        poster: String? = null
    ) = MetaPreview(
        id = id,
        type = ContentType.TV,
        name = name,
        poster = poster,
        posterShape = PosterShape.LANDSCAPE,
        background = null,
        logo = logo,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private fun MetaPreview.toChannel() = toLiveTvChannel(
        addonBaseUrl = ADDON,
        addonName = "Addon",
        catalogId = CATALOG,
        catalogName = "Vivo",
        apiType = "tv"
    )

    private fun channel(
        id: String = "c1",
        addonBaseUrl: String = ADDON,
        catalogId: String = CATALOG
    ) = LiveTvChannel(
        id = id,
        addonBaseUrl = addonBaseUrl,
        addonName = "Addon",
        catalogIds = setOf(catalogId),
        catalogName = "Vivo",
        apiType = "tv",
        name = "Canal $id",
        logoUrl = null,
        posterUrl = null,
        description = null,
        genres = emptyList()
    )

    private companion object {
        const val ADDON = "https://addon.test/token"
        const val CATALOG = "esencial-play-live-todo"
    }
}
