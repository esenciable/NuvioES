package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The V3 ordering contract: native VOD source names go to the FRONT of `pluginOrder`, so their
 * groups sort before every scraper's group, while names absent from both lists (unknown
 * arrivals) still land last — `orderAddonStreams` semantics untouched.
 */
class StreamPluginOrderTest {

    private fun group(name: String) = AddonStreams(
        addonName = name,
        addonLogo = null,
        streams = emptyList(),
    )

    @Test
    fun `native names precede scraper names`() {
        val order = nativeFirstPluginOrder(
            nativeSourceNames = listOf("Magis VOD"),
            scraperNames = listOf("Torrentio", "MediaFusion"),
        )
        assertEquals(listOf("Magis VOD", "Torrentio", "MediaFusion"), order)
    }

    @Test
    fun `native group sorts first, scrapers follow registry order, unknown names last`() {
        val pluginOrder = nativeFirstPluginOrder(
            nativeSourceNames = listOf("Magis VOD"),
            scraperNames = listOf("Torrentio", "MediaFusion"),
        )
        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            // Arrival order: unknown first, scrapers shuffled — the sort must not care.
            listOf(group("Late Arrival"), group("MediaFusion"), group("Torrentio"), group("Magis VOD")),
            installedOrder = emptyList(),
            pluginOrder = pluginOrder,
        )
        assertEquals(
            listOf("Magis VOD", "Torrentio", "MediaFusion", "Late Arrival"),
            ordered.map { it.addonName },
        )
    }

    @Test
    fun `empty native list keeps the scraper-only order`() {
        val order = nativeFirstPluginOrder(
            nativeSourceNames = emptyList(),
            scraperNames = listOf("Torrentio", "MediaFusion"),
        )
        assertEquals(listOf("Torrentio", "MediaFusion"), order)
    }
}
