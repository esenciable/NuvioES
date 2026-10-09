package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cross-group URL dedup: a stream whose URL was already provided by an EARLIER group is dropped,
 * keeping the first occurrence. Groups are ordered by `pluginOrder` (native sources first), so
 * the native row survives and the scraper's duplicate disappears — while the scraper still acts
 * as a fallback whenever the native source yields nothing.
 */
class CrossGroupStreamDedupTest {

    private fun group(name: String, vararg streams: Stream) = AddonStreams(
        addonName = name,
        addonLogo = null,
        streams = streams.toList(),
    )

    private fun stream(
        url: String?,
        addonName: String,
        name: String? = null,
        infoHash: String? = null,
        clientResolve: StreamClientResolve? = null,
    ) = Stream(
        name = name,
        title = name,
        description = null,
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = addonName,
        addonLogo = null,
        clientResolve = clientResolve,
    )

    private fun clientResolveStream(addonName: String) = stream(
        url = null,
        addonName = addonName,
        name = "Resolve",
        clientResolve = StreamClientResolve(
            type = null,
            infoHash = "abc123",
            fileIdx = null,
            magnetUri = null,
            sources = null,
            torrentName = null,
            filename = null,
            mediaType = null,
            mediaId = null,
            mediaOnlyId = null,
            title = null,
            season = null,
            episode = null,
            service = null,
            serviceIndex = null,
            serviceExtension = null,
            isCached = null,
        ),
    )

    @Test
    fun `later group's stream with the same URL is dropped`() {
        val native = group("Magis VOD", stream("https://cdn.example/video.m3u8", "Magis VOD"))
        val scraper = group("Magis", stream("https://cdn.example/video.m3u8", "Magis VOD - Auto"))

        val result = deduplicateStreamsAcrossGroups(listOf(native, scraper))

        assertEquals(listOf("Magis VOD"), result.groups.map { it.addonName })
        assertEquals(1, result.groups.single().streams.size)
    }

    @Test
    fun `later group's stream with a different query parameter is kept`() {
        val native = group("Magis VOD", stream("https://cdn.example/video.m3u8?sig=aaa", "Magis VOD"))
        val scraper = group("Magis", stream("https://cdn.example/video.m3u8?sig=bbb", "Magis VOD - Auto"))

        val result = deduplicateStreamsAcrossGroups(listOf(native, scraper))

        assertEquals(listOf("Magis VOD", "Magis"), result.groups.map { it.addonName })
        assertEquals(2, result.groups.sumOf { it.streams.size })
    }

    @Test
    fun `group emptied by the dedup disappears and reports its name`() {
        val native = group(
            "Magis VOD",
            stream("https://cdn.example/video.m3u8", "Magis VOD"),
            stream("https://cdn.example/other.m3u8", "Magis VOD"),
        )
        val scraper = group(
            "Magis",
            stream("https://cdn.example/video.m3u8", "Magis VOD - Auto"),
            stream("https://cdn.example/other.m3u8", "Magis VOD - Auto"),
        )
        val other = group("Torrentio", stream("https://torrent.example/a.mkv", "Torrentio"))

        val result = deduplicateStreamsAcrossGroups(listOf(native, scraper, other))

        assertEquals(listOf("Magis VOD", "Torrentio"), result.groups.map { it.addonName })
        assertEquals(setOf("Magis"), result.droppedGroupNames)
    }

    @Test
    fun `partially deduplicated group keeps its remaining streams`() {
        val native = group("Magis VOD", stream("https://cdn.example/video.m3u8", "Magis VOD"))
        val scraper = group(
            "Magis",
            stream("https://cdn.example/video.m3u8", "Magis", name = "Magis VOD - Auto"),
            stream("https://cdn.example/unique.m3u8", "Magis", name = "Magis - Other"),
        )

        val result = deduplicateStreamsAcrossGroups(listOf(native, scraper))

        assertEquals(listOf("Magis VOD", "Magis"), result.groups.map { it.addonName })
        assertEquals(listOf("Magis - Other"), result.groups.last().streams.map { it.name })
        assertEquals(emptySet<String>(), result.droppedGroupNames)
    }

    @Test
    fun `survivor is the earlier group's stream`() {
        val native = group(
            "Magis VOD",
            stream("https://cdn.example/video.m3u8", "Magis VOD", name = "Magis VOD"),
        )
        val scraper = group(
            "Magis",
            stream("https://cdn.example/video.m3u8", "Magis", name = "Magis VOD - Auto"),
        )

        val result = deduplicateStreamsAcrossGroups(listOf(native, scraper))

        assertEquals("Magis VOD", result.groups.single().streams.single().addonName)
        assertEquals("Magis VOD", result.groups.single().streams.single().name)
    }

    @Test
    fun `streams without a URL are never touched`() {
        val native = group(
            "Magis VOD",
            stream("https://cdn.example/video.m3u8", "Magis VOD"),
        )
        val debrid = group(
            "Debrid",
            stream(url = null, addonName = "Debrid", name = "Torrent", infoHash = "deadbeef"),
            clientResolveStream("Debrid"),
        )

        val result = deduplicateStreamsAcrossGroups(listOf(native, debrid))

        assertEquals(listOf("Magis VOD", "Debrid"), result.groups.map { it.addonName })
        assertEquals(2, result.groups.last().streams.size)
        assertEquals(setOf("Debrid"), result.groups.last().streams.map { it.addonName }.toSet())
    }

    @Test
    fun `no-URL stream in an earlier group does not claim a later group's URL`() {
        val debrid = group("Debrid", stream(url = null, addonName = "Debrid", name = "Torrent", infoHash = "deadbeef"))
        val native = group("Magis VOD", stream("https://cdn.example/video.m3u8", "Magis VOD"))
        val scraper = group("Magis", stream("https://cdn.example/video.m3u8", "Magis VOD - Auto"))

        val result = deduplicateStreamsAcrossGroups(listOf(debrid, native, scraper))

        assertEquals(listOf("Debrid", "Magis VOD"), result.groups.map { it.addonName })
    }

    @Test
    fun `same URL twice within ONE group keeps today's behaviour`() {
        val native = group(
            "Magis VOD",
            stream("https://cdn.example/video.m3u8", "Magis VOD"),
            stream("https://cdn.example/video.m3u8", "Magis VOD (copy)"),
        )

        val result = deduplicateStreamsAcrossGroups(listOf(native))

        assertEquals(listOf("Magis VOD"), result.groups.map { it.addonName })
        assertEquals(2, result.groups.single().streams.size)
        assertEquals(emptySet<String>(), result.droppedGroupNames)
    }

    @Test
    fun `URL comparison trims surrounding whitespace but is otherwise exact`() {
        val native = group("Magis VOD", stream("https://cdn.example/video.m3u8", "Magis VOD"))
        val scraper = group("Magis", stream("  https://cdn.example/video.m3u8  ", "Magis VOD - Auto"))

        val result = deduplicateStreamsAcrossGroups(listOf(native, scraper))

        assertEquals(listOf("Magis VOD"), result.groups.map { it.addonName })
    }

    @Test
    fun `case differences in the URL are not merged`() {
        val native = group("Magis VOD", stream("https://cdn.example/Video.m3u8", "Magis VOD"))
        val scraper = group("Magis", stream("https://cdn.example/video.m3u8", "Magis VOD - Auto"))

        val result = deduplicateStreamsAcrossGroups(listOf(native, scraper))

        assertEquals(listOf("Magis VOD", "Magis"), result.groups.map { it.addonName })
    }

    @Test
    fun `groups that arrive empty are passed through untouched`() {
        val native = group("Magis VOD", stream("https://cdn.example/video.m3u8", "Magis VOD"))
        val empty = group("Empty Source")

        val result = deduplicateStreamsAcrossGroups(listOf(native, empty))

        assertEquals(listOf("Magis VOD", "Empty Source"), result.groups.map { it.addonName })
        assertEquals(emptySet<String>(), result.droppedGroupNames)
    }
}
