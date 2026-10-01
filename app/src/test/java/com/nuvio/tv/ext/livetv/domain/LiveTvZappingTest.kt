package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ---------------------------------------------------------------------
    // The full-pass walk (zapCandidates): every channel in the step direction,
    // tried once, wrapping, stopping before the origin.
    // ---------------------------------------------------------------------

    @Test
    fun `zap candidates visits every other channel in order`() {
        val list = channels("a", "b", "c", "d")

        assertEquals(
            listOf(key("b"), key("c"), key("d")),
            LiveTvZapping.zapCandidates(list, key("a"), step = 1)
        )
        assertEquals(
            listOf(key("c"), key("b"), key("a")),
            LiveTvZapping.zapCandidates(list, key("d"), step = -1)
        )
    }

    @Test
    fun `zap candidates wrap around the edges`() {
        val list = channels("a", "b", "c")

        // Forward from the last row lands on the first and keeps going to just before the origin.
        assertEquals(
            listOf(key("a"), key("b")),
            LiveTvZapping.zapCandidates(list, key("c"), step = 1)
        )
        // Backward from the first row lands on the last.
        assertEquals(
            listOf(key("c"), key("b")),
            LiveTvZapping.zapCandidates(list, key("a"), step = -1)
        )
    }

    @Test
    fun `zap candidates cross a dead run without truncating`() {
        // A stretch of source-less channels must not cut the walk short: the sequence carries the
        // WHOLE pass, dead channels included, so the caller can keep trying past them.
        val list = channels("a", "dead1", "dead2", "dead3", "e")

        assertEquals(
            listOf(key("dead1"), key("dead2"), key("dead3"), key("e")),
            LiveTvZapping.zapCandidates(list, key("a"), step = 1)
        )
    }

    @Test
    fun `an all-dead list still yields one full pass`() {
        // The helper does not know playability -- it orders the attempts. With the origin gone from
        // the list (a filter change mid-zap), the pass covers every channel exactly once and the
        // caller decides failure after trying them all.
        val list = channels("dead1", "dead2", "dead3")

        assertEquals(
            listOf(key("dead1"), key("dead2"), key("dead3")),
            LiveTvZapping.zapCandidates(list, key("gone"), step = 1)
        )
        assertEquals(
            listOf(key("dead3"), key("dead2"), key("dead1")),
            LiveTvZapping.zapCandidates(list, key("gone"), step = -1)
        )
    }

    @Test
    fun `a single channel yields nothing to try when it is the origin`() {
        // There is no other channel to move to; the walk is empty rather than re-trying the channel
        // that is already playing.
        val list = channels("solo")

        assertTrue(LiveTvZapping.zapCandidates(list, key("solo"), step = 1).isEmpty())
        assertTrue(LiveTvZapping.zapCandidates(list, key("solo"), step = -1).isEmpty())
    }

    @Test
    fun `a single channel still yields itself when the origin is missing`() {
        val list = channels("solo")

        assertEquals(listOf(key("solo")), LiveTvZapping.zapCandidates(list, key("gone"), step = 1))
    }

    @Test
    fun `zap candidates on an empty list are empty`() {
        assertTrue(LiveTvZapping.zapCandidates(emptyList(), key("a"), step = 1).isEmpty())
        assertTrue(LiveTvZapping.zapCandidates(emptyList(), null, step = -1).isEmpty())
    }

    private fun channels(vararg ids: String): List<LiveTvChannel> = ids.map { channel(it) }

    private fun channel(id: String) = LiveTvChannel(
        id = id,
        addonBaseUrl = addon,
        addonName = "Addon",
        catalogIds = setOf("vivo"),
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
