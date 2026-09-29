package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.ext.livetv.domain.LiveTvPlayableStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTvPreviewCacheTest {

    @Test
    fun `remembers a resolved stream`() {
        val cache = LiveTvPreviewCache()
        cache.put("a", stream("https://cdn.test/a.m3u8"))

        assertEquals("https://cdn.test/a.m3u8", cache.get("a")?.url)
        assertNull(cache.get("b"))
    }

    @Test
    fun `evicts the least recently used entry`() {
        val cache = LiveTvPreviewCache(maxEntries = 2)
        cache.put("a", stream("https://cdn.test/a.m3u8"))
        cache.put("b", stream("https://cdn.test/b.m3u8"))
        cache.put("c", stream("https://cdn.test/c.m3u8"))

        assertNull("a was the oldest and had to go", cache.get("a"))
        assertEquals(2, cache.size())
    }

    @Test
    fun `reading an entry counts as using it`() {
        // The channels the user keeps coming back to must survive while the ones they skimmed past age
        // out. Without access ordering this would evict "a", which is the one in active use.
        val cache = LiveTvPreviewCache(maxEntries = 2)
        cache.put("a", stream("https://cdn.test/a.m3u8"))
        cache.put("b", stream("https://cdn.test/b.m3u8"))
        cache.get("a")
        cache.put("c", stream("https://cdn.test/c.m3u8"))

        assertEquals("a is still here", "https://cdn.test/a.m3u8", cache.get("a")?.url)
        assertNull("b was the least recently used", cache.get("b"))
    }

    @Test
    fun `a channel can be forgotten when its stream stops working`() {
        val cache = LiveTvPreviewCache()
        cache.put("a", stream("https://cdn.test/a.m3u8"))

        cache.forget("a")

        assertNull(cache.get("a"))
    }

    @Test
    fun `clearing empties it`() {
        val cache = LiveTvPreviewCache()
        cache.put("a", stream("https://cdn.test/a.m3u8"))
        cache.put("b", stream("https://cdn.test/b.m3u8"))

        cache.clear()

        assertEquals(0, cache.size())
    }

    private fun stream(url: String) = LiveTvPlayableStream(url = url, name = null, headers = null)
}
