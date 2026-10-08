package com.nuvio.tv.core.player

import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamAutoPlaySelectorTest {

    @Test
    fun `orderAddonStreams follows installed addon order and leaves plugins last`() {
        val plugin = addonStreams("Plugin")
        val addonB = addonStreams("AddonB")
        val addonA = addonStreams("AddonA")
        val unknown = addonStreams("UnknownPlugin")

        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            streams = listOf(plugin, addonB, addonA, unknown),
            installedOrder = listOf("AddonA", "AddonB")
        )

        assertEquals(listOf(addonA, addonB, plugin, unknown), ordered)
    }

    @Test
    fun `orderAddonStreams orders plugin groups by pluginOrder with Magis first`() {
        val other = addonStreams("OtherPlugin")
        val magis = addonStreams("Magis")
        val addonA = addonStreams("AddonA")

        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            streams = listOf(other, magis, addonA),
            installedOrder = listOf("AddonA"),
            pluginOrder = listOf("Magis", "OtherPlugin")
        )

        assertEquals(listOf(addonA, magis, other), ordered)
    }

    @Test
    fun `orderAddonStreams keeps unknown plugin groups after known ones in arrival order`() {
        val unknownFirst = addonStreams("UnknownA")
        val magis = addonStreams("Magis")
        val unknownSecond = addonStreams("UnknownB")

        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            streams = listOf(unknownFirst, magis, unknownSecond),
            installedOrder = emptyList(),
            pluginOrder = listOf("Magis")
        )

        assertEquals(listOf(magis, unknownFirst, unknownSecond), ordered)
    }

    @Test
    fun `orderAddonStreams uses first occurrence for duplicate pluginOrder names and keeps arrival order for equal names`() {
        val magisArrivalOne = addonStreams("Magis")
        val other = addonStreams("OtherPlugin")
        val magisArrivalTwo = addonStreams("Magis")

        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            streams = listOf(magisArrivalTwo, other, magisArrivalOne),
            installedOrder = emptyList(),
            pluginOrder = listOf("Magis", "OtherPlugin", "Magis")
        )

        // Magis groups keep their relative arrival order; OtherPlugin ranks after Magis.
        assertEquals(listOf(magisArrivalTwo, magisArrivalOne, other), ordered)
    }

    @Test
    fun `orderAddonStreams with empty pluginOrder keeps plugin arrival order`() {
        val pluginB = addonStreams("PluginB")
        val pluginA = addonStreams("PluginA")
        val addonA = addonStreams("AddonA")

        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            streams = listOf(pluginB, pluginA, addonA),
            installedOrder = listOf("AddonA"),
            pluginOrder = emptyList()
        )

        assertEquals(listOf(addonA, pluginB, pluginA), ordered)
    }

    @Test
    fun `orderAddonStreams keeps direct debrid entries first even with pluginOrder`() {
        val plugin = addonStreams("Magis", stream(addonName = "Magis", url = "https://example.com/m.m3u8"))
        val directDebrid = addonStreams(
            "DebridAddon",
            directDebridStream("DebridAddon")
        )
        val addonA = addonStreams("AddonA")

        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            streams = listOf(plugin, directDebrid, addonA),
            installedOrder = listOf("AddonA"),
            pluginOrder = listOf("Magis")
        )

        assertEquals(listOf(directDebrid, addonA, plugin), ordered)
    }

    @Test
    fun `orderAddonStreams combines installed addon order and plugin order`() {
        val pluginLate = addonStreams("PluginLate")
        val pluginFirst = addonStreams("PluginFirst")
        val addonB = addonStreams("AddonB")
        val addonA = addonStreams("AddonA")

        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            streams = listOf(pluginLate, addonB, pluginFirst, addonA),
            installedOrder = listOf("AddonA", "AddonB"),
            pluginOrder = listOf("PluginFirst", "PluginLate")
        )

        assertEquals(listOf(addonA, addonB, pluginFirst, pluginLate), ordered)
    }

    @Test
    fun `bingeGroup-first selects matching stream before first stream mode`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "1080p",
            bingeGroup = "other-group"
        )
        val preferred = stream(
            addonName = "AddonB",
            url = "https://example.com/preferred.m3u8",
            name = "720p",
            bingeGroup = "same-group"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, preferred),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(preferred, selected)
    }

    @Test
    fun `falls back to normal mode when no bingeGroup match exists`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "First",
            bingeGroup = "group-a"
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            name = "Second",
            bingeGroup = "group-b"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "missing-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(first, selected)
    }

    @Test
    fun `bingeGroup-first respects source and addon plugin filters`() {
        val filteredOutAddonMatch = stream(
            addonName = "AddonFilteredOut",
            url = "https://example.com/addon-match.m3u8",
            bingeGroup = "same-group"
        )
        val allowedPluginMatch = stream(
            addonName = "PluginAllowed",
            url = "https://example.com/plugin-match.m3u8",
            bingeGroup = "same-group"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(filteredOutAddonMatch, allowedPluginMatch),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ENABLED_PLUGINS_ONLY,
            installedAddonNames = setOf("AddonFilteredOut"),
            selectedAddons = emptySet(),
            selectedPlugins = setOf("PluginAllowed"),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true
        )

        if (AppFeaturePolicy.pluginsEnabled) {
            assertEquals(allowedPluginMatch, selected)
        } else {
            assertEquals(filteredOutAddonMatch, selected)
        }
    }

    @Test
    fun `regex mode still works when bingeGroup missing or no match`() {
        val nonMatch = stream(
            addonName = "AddonA",
            url = "https://example.com/a.m3u8",
            name = "720p"
        )
        val regexMatch = stream(
            addonName = "AddonB",
            url = "https://example.com/b.m3u8",
            name = "2160p Remux"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(nonMatch, regexMatch),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "2160p|Remux",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "unmatched-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(regexMatch, selected)
    }

    @Test
    fun `blank preferredBingeGroup behaves as disabled`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            bingeGroup = "group-a"
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            bingeGroup = "group-b"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "   ",
            preferBingeGroupInSelection = true
        )

        assertEquals(first, selected)
    }

    @Test
    fun `manual mode auto-selects matching bingeGroup when prefer enabled`() {
        // Binge-group continuity is intentionally allowed in MANUAL mode so
        // next-episode / resume can skip the picker when a group was locked in.
        val matched = stream(
            addonName = "AddonA",
            url = "https://example.com/match.m3u8",
            bingeGroup = "same-group"
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(matched),
            mode = StreamAutoPlayMode.MANUAL,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true
        )

        assertEquals(matched, selected)
    }

    @Test
    fun `first stream skips checking and not cached local debrid streams`() {
        val checking = stream(
            addonName = "AddonA",
            name = "Checking",
            infoHash = "abc123",
            cacheState = StreamDebridCacheState.CHECKING
        )
        val notCached = stream(
            addonName = "AddonA",
            name = "Not cached",
            infoHash = "def456",
            cacheState = StreamDebridCacheState.NOT_CACHED
        )
        val unknown = stream(
            addonName = "AddonA",
            name = "Unknown",
            infoHash = "unknown",
            cacheState = StreamDebridCacheState.UNKNOWN
        )
        val cached = stream(
            addonName = "AddonA",
            name = "Cached",
            infoHash = "ghi789",
            cacheState = StreamDebridCacheState.CACHED
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(checking, notCached, unknown, cached),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet()
        )

        assertEquals(cached, selected)
    }

    @Test
    fun `orderAddonStreams keeps cached local torrent groups in installed addon order`() {
        val regular = addonStreams(
            "AddonA",
            stream(
                addonName = "AddonA",
                url = "https://example.com/regular.m3u8"
            )
        )
        val cachedDebrid = addonStreams(
            "AddonB",
            stream(
                addonName = "AddonB",
                infoHash = "abc123",
                cacheState = StreamDebridCacheState.CACHED
            )
        )

        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            streams = listOf(regular, cachedDebrid),
            installedOrder = listOf("AddonA", "AddonB")
        )

        assertEquals(listOf(regular, cachedDebrid), ordered)
    }

    private fun stream(
        addonName: String,
        url: String? = null,
        name: String? = null,
        bingeGroup: String? = null,
        infoHash: String? = null,
        cacheState: StreamDebridCacheState? = null,
        clientResolve: StreamClientResolve? = null
    ): Stream = Stream(
        name = name,
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = bingeGroup,
            countryWhitelist = null,
            proxyHeaders = null
        ),
        addonName = addonName,
        addonLogo = null,
        debridCacheStatus = cacheState?.let {
            StreamDebridCacheStatus(
                providerId = "torbox",
                providerName = "Torbox",
                state = it
            )
        },
        clientResolve = clientResolve
    )

    private fun directDebridStream(addonName: String): Stream = stream(
        addonName = addonName,
        url = "https://example.com/direct-debrid.m3u8",
        clientResolve = StreamClientResolve(
            type = "debrid",
            infoHash = null,
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
            service = "torbox",
            serviceIndex = null,
            serviceExtension = null,
            isCached = true
        )
    )

    private fun addonStreams(
        addonName: String,
        vararg streams: Stream
    ): AddonStreams = AddonStreams(
        addonName = addonName,
        addonLogo = null,
        streams = streams.toList()
    )
}
