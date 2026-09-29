package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.ext.livetv.domain.model.EpgSource
import com.nuvio.tv.ext.livetv.domain.model.EpgSourceOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgSourceDiscoveryTest {

    @Test
    fun `derives the guide url from the addon base`() {
        // The addon publishes its own convention: base = manifest URL minus /manifest.json, and the
        // guide sits at base + /epg.xml.
        assertEquals(
            "http://192.168.1.10:51546/abc123token/epg.xml",
            EpgSourceDiscovery.epgUrlForAddon("http://192.168.1.10:51546/abc123token")
        )
    }

    @Test
    fun `strips a manifest suffix that is still present`() {
        assertEquals(
            "https://addon.test/token/epg.xml",
            EpgSourceDiscovery.epgUrlForAddon("https://addon.test/token/manifest.json")
        )
    }

    @Test
    fun `keeps the query in the right place`() {
        // The trap: appending to the raw string would give
        // "https://addon.test/token?x=1/epg.xml", where the query swallows the path. Upstream's
        // canonicaliser keeps the query aside, and this mirrors it.
        assertEquals(
            "https://addon.test/token/epg.xml?x=1",
            EpgSourceDiscovery.epgUrlForAddon("https://addon.test/token?x=1")
        )
        assertEquals(
            "https://addon.test/token/epg.xml?x=1",
            EpgSourceDiscovery.epgUrlForAddon("https://addon.test/token/manifest.json?x=1")
        )
    }

    @Test
    fun `tolerates trailing slashes and blanks`() {
        assertEquals(
            "https://addon.test/token/epg.xml",
            EpgSourceDiscovery.epgUrlForAddon("  https://addon.test/token///  ")
        )
        assertNull(EpgSourceDiscovery.epgUrlForAddon("   "))
        assertNull(EpgSourceDiscovery.epgUrlForAddon("/manifest.json"))
    }

    @Test
    fun `the addon source id does not depend on the display name`() {
        val first = EpgSourceDiscovery.fromAddon("https://addon.test/token", "Canal 13")
        val renamed = EpgSourceDiscovery.fromAddon("https://addon.test/token", "El Trece")

        assertEquals(first?.id, renamed?.id)
        assertTrue(first?.origin is EpgSourceOrigin.FromAddon)
    }

    @Test
    fun `the built-in fallbacks cover hispanic countries`() {
        val tags = EpgSourceDiscovery.BUILT_IN.map { it.id.removePrefix("builtin:") }

        // The reference fork shipped only BR1/PT1/US1, which left the guide empty for Spanish
        // speaking users. These are the tags the provider publishes.
        assertTrue(tags.containsAll(listOf("AR1", "MX1", "CO1", "CL1", "PE1", "UY1", "ES1")))
        assertTrue(tags.containsAll(listOf("US1", "BR1", "PT1")))
        assertTrue(
            EpgSourceDiscovery.BUILT_IN.all {
                it.url == "https://epgshare01.online/epgshare01/epg_ripper_${it.id.removePrefix("builtin:")}.xml.gz"
            }
        )
    }

    @Test
    fun `orders addon sources first, then the user's, then the fallbacks`() {
        val user = EpgSource("user:1", "Mi guía", "https://mine.test/epg.xml", EpgSourceOrigin.User)

        val sources = EpgSourceDiscovery.discover(
            addons = listOf("https://addon.test/token" to "Esencial Play"),
            userSources = listOf(user)
        )

        assertEquals("addon:https://addon.test/token", sources.first().id)
        assertEquals("user:1", sources[1].id)
        assertTrue(sources.drop(2).all { it.origin is EpgSourceOrigin.BuiltIn })
    }

    @Test
    fun `lets the user switch every source off`() {
        // RF-38: in exchange for telling the user the guide comes from third parties, disabling all
        // of them has to be possible.
        val allIds = EpgSourceDiscovery.discover(
            addons = listOf("https://addon.test/token" to "Esencial Play")
        ).map { it.id }.toSet()

        assertTrue(EpgSourceDiscovery.discover(listOf(), disabledIds = allIds).isEmpty())
    }

    @Test
    fun `collapses duplicate addon sources`() {
        val sources = EpgSourceDiscovery.discover(
            addons = listOf(
                "https://addon.test/token" to "Esencial Play",
                "https://addon.test/token/" to "Esencial Play"
            )
        )

        assertEquals(1, sources.count { it.origin is EpgSourceOrigin.FromAddon })
    }
}
