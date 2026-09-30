package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvSearchTest {

    private val channels = listOf(
        channel("A&E HD"),
        channel("Canal 13 de Argentina"),
        channel("CANAL 13 FHD"),
        channel("Televisión Española"),
        channel("NBA Eventos"),
        channel("ECDF")
    )

    @Test
    fun `a blank query changes nothing`() {
        assertEquals(channels, channels.matching(""))
        assertEquals(channels, channels.matching("   "))
    }

    @Test
    fun `matches part of a name`() {
        assertEquals(
            listOf("NBA Eventos"),
            channels.matching("nba").map { it.name }
        )
    }

    @Test
    fun `ignores case`() {
        assertEquals(channels.matching("ecdf"), channels.matching("ECDF"))
    }

    @Test
    fun `ignores accents and punctuation`() {
        // Typing an accent on a television remote is miserable, so the search must not require it.
        assertEquals(
            listOf("Televisión Española"),
            channels.matching("television espanola").map { it.name }
        )
        assertEquals(
            listOf("A&E HD"),
            channels.matching("a&e").map { it.name }
        )
    }

    @Test
    fun `quality markers do not hide a channel`() {
        // The same normaliser the guide uses, so "Canal 13 HD" and "Canal 13 FHD" are both found by
        // "canal 13" -- and both come back, because they really are two entries in the addon.
        assertEquals(
            listOf("Canal 13 de Argentina", "CANAL 13 FHD"),
            channels.matching("canal 13").map { it.name }
        )
    }

    @Test
    fun `no match gives an empty list, not a fallback`() {
        assertTrue(channels.matching("canal que no existe").isEmpty())
    }

    @Test
    fun `matches across different channels without losing any`() {
        assertEquals(
            listOf("ECDF"),
            channels.matching("ecdf").map { it.name }
        )
    }

    @Test
    fun `does not match across two unrelated words`() {
        // The false positive the first run caught: "a&e" normalised to "ae", which lives inside
        // "nbaeventos". Keeping words apart is what makes this query mean what it says.
        assertEquals(listOf("A&E HD"), channels.matching("a&e").map { it.name })
    }

    @Test
    fun `matches while the word is still being typed`() {
        // Prefix per word, not whole word, so the list narrows as the user types.
        assertEquals(
            listOf("Canal 13 de Argentina", "CANAL 13 FHD"),
            channels.matching("can").map { it.name }
        )
    }

    @Test
    fun `every typed word has to match, not just one`() {
        assertTrue(channels.matching("nba ecdf").isEmpty())
    }

    private fun channel(name: String) = LiveTvChannel(
        id = name,
        addonBaseUrl = "https://addon.test/token",
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
}
