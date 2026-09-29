package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Zapping, at the level that matters: which channel a single UP/DOWN lands on.
 *
 * The function only ever sees the list it is handed, which is exactly the property that keeps zapping
 * inside the parental filter and inside the selected category. The last test states that on purpose.
 */
class LiveTvZappingTest {

    private val addon = "https://addon.test/token"

    @Test
    fun `next advances one channel`() {
        val list = channels("a", "b", "c")

        assertEquals(key("b"), LiveTvZapping.nextKey(list, key("a")))
        assertEquals(key("c"), LiveTvZapping.nextKey(list, key("b")))
    }

    @Test
    fun `previous goes back one channel`() {
        val list = channels("a", "b", "c")

        assertEquals(key("a"), LiveTvZapping.previousKey(list, key("b")))
        assertEquals(key("b"), LiveTvZapping.previousKey(list, key("c")))
    }

    @Test
    fun `next from the last channel wraps to the first`() {
        val list = channels("a", "b", "c")

        assertEquals(key("a"), LiveTvZapping.nextKey(list, key("c")))
    }

    @Test
    fun `previous from the first channel wraps to the last`() {
        val list = channels("a", "b", "c")

        assertEquals(key("c"), LiveTvZapping.previousKey(list, key("a")))
    }

    @Test
    fun `a single channel wraps onto itself in both directions`() {
        val list = channels("solo")

        assertEquals(key("solo"), LiveTvZapping.nextKey(list, key("solo")))
        assertEquals(key("solo"), LiveTvZapping.previousKey(list, key("solo")))
    }

    @Test
    fun `a current channel missing from the list starts next from the first`() {
        // The current channel went away: a filter, a refresh, a search. Forward lands on row 1.
        val list = channels("a", "b", "c")

        assertEquals(key("a"), LiveTvZapping.nextKey(list, key("gone")))
    }

    @Test
    fun `a current channel missing from the list starts previous from the last`() {
        val list = channels("a", "b", "c")

        assertEquals(key("c"), LiveTvZapping.previousKey(list, key("gone")))
    }

    @Test
    fun `a null current channel starts next from the first and previous from the last`() {
        val list = channels("a", "b", "c")

        assertEquals(key("a"), LiveTvZapping.nextKey(list, null))
        assertEquals(key("c"), LiveTvZapping.previousKey(list, null))
    }

    @Test
    fun `an empty list is a no-op`() {
        assertNull(LiveTvZapping.nextKey(emptyList(), key("a")))
        assertNull(LiveTvZapping.previousKey(emptyList(), key("a")))
        assertNull(LiveTvZapping.nextKey(emptyList(), null))
    }

    @Test
    fun `zapping never leaves the list it was given`() {
        // The list handed in is already filtered. Every step of a walk that wraps around three times
        // must stay among its three channels -- a channel outside it must never be returned.
        val visible = channels("a", "b", "c")
        val visibleKeys = visible.map { it.stableKey }.toSet()
        var current = visible.first().stableKey

        repeat(3 * visible.size) {
            current = LiveTvZapping.nextKey(visible, current)!!
            assertEquals("next stayed inside the filtered list", true, current in visibleKeys)
        }
    }

    private fun channels(vararg ids: String): List<LiveTvChannel> = ids.map { channel(it) }

    private fun channel(id: String) = LiveTvChannel(
        id = id,
        addonBaseUrl = addon,
        addonName = "Addon",
        catalogId = "vivo",
        catalogName = "En vivo",
        apiType = "tv",
        name = "Canal $id",
        logoUrl = null,
        posterUrl = null,
        description = null,
        genres = emptyList()
    )

    private fun key(id: String) = "$addon|$id"
}
