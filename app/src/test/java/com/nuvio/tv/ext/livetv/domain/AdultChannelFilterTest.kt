package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdultChannelFilterTest {

    @Test
    fun `flags the obvious ones`() {
        listOf(
            "Playboy TV",
            "Penthouse HD",
            "Hustler TV",
            "Blue Hustler",
            "Brazzers TV",
            "Dorcel TV",
            "Daring TV",
            "SEXTREME",
            "Redlight HD",
            "Adult Channel",
            "Canal Adultos",
            "XXX Live",
            "Erotico 1",
            "Pornografia HD"
        ).forEach { name ->
            assertTrue("should be flagged: $name", AdultChannelFilter.isAdultText(name))
        }
    }

    @Test
    fun `flags the compact forms that never appear as a token`() {
        assertTrue(AdultChannelFilter.isAdultText("Canal +18"))
        assertTrue(AdultChannelFilter.isAdultText("18+ TV"))
        assertTrue(AdultChannelFilter.isAdultText("Canal 18plus"))
        assertTrue(AdultChannelFilter.isAdultText("XXXX"))
    }

    @Test
    fun `does not flag ordinary channels`() {
        // The false positives that matter: a filter that hides these is a filter the user turns off, and
        // then it protects nobody.
        listOf(
            "Canal 13",
            "Discovery H&H",
            "NBA Eventos",
            "A&E HD",
            "ECDF FHD",
            "AMC HD",
            "NetFlix Eventos",
            "A3S",
            "LGVIP La Gala",
            "TV Publica",
            "Cine Latino",
            "ESPN 2"
        ).forEach { name ->
            assertFalse("should NOT be flagged: $name", AdultChannelFilter.isAdultText(name))
        }
    }

    @Test
    fun `does not match on a substring`() {
        // Substring matching is how a filter starts hiding channels its list never intended.
        assertFalse("Sussex contains sex", AdultChannelFilter.isAdultText("Sussex TV"))
        assertFalse("an adulto-ish word is not the token", AdultChannelFilter.isAdultText("Adultosmayores TV"))
    }

    @Test
    fun `hot news is not adult news`() {
        // Why `hot` is not on the list at all.
        assertFalse(AdultChannelFilter.isAdultText("Hot News"))
        assertFalse(AdultChannelFilter.isAdultText("Hot Wheels TV"))
    }

    @Test
    fun `the phrase beats the word for adult swim`() {
        assertFalse(AdultChannelFilter.isAdultText("Adult Swim"))
        assertFalse(AdultChannelFilter.isAdultText("Adult Swim HD"))
        assertFalse(AdultChannelFilter.isAdultText("adultswim"))
        // But the word on its own still counts.
        assertTrue(AdultChannelFilter.isAdultText("Adult Channel"))
    }

    @Test
    fun `ignores case and accents`() {
        assertTrue(AdultChannelFilter.isAdultText("ERÓTICO"))
        assertTrue(AdultChannelFilter.isAdultText("erotico"))
        assertTrue(AdultChannelFilter.isAdultText("Pornografía"))
    }

    @Test
    fun `flags a channel whose genre says so even when the name does not`() {
        val channel = channel(name = "Canal 5", genres = listOf("Adult"))

        assertTrue(AdultChannelFilter.isAdult(channel))
    }

    @Test
    fun `flags a channel whose catalog is the adult catalog`() {
        val channel = channel(name = "Canal 5", genres = emptyList(), catalogName = "Adultos")

        assertTrue(AdultChannelFilter.isAdult(channel))
    }

    @Test
    fun `an empty or blank name is never flagged`() {
        assertFalse(AdultChannelFilter.isAdultText(""))
        assertFalse(AdultChannelFilter.isAdultText("   "))
        assertFalse(AdultChannelFilter.isAdultText("---"))
    }

    private fun channel(
        name: String,
        genres: List<String>,
        catalogName: String = "TV · todo"
    ) = LiveTvChannel(
        id = "c1",
        addonBaseUrl = "https://addon.test/token",
        addonName = "Addon",
        catalogId = "vivo",
        catalogName = catalogName,
        apiType = "tv",
        name = name,
        logoUrl = null,
        posterUrl = null,
        description = null,
        genres = genres
    )
}
