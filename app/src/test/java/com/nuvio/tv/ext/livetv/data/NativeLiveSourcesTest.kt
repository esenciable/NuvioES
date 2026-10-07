package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.ext.livetv.domain.LiveTvCatalog
import com.nuvio.tv.ext.livetv.domain.LiveTvPlayableStream
import com.nuvio.tv.ext.livetv.domain.NativeLiveSource
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list of native live sources is the seam the ViewModel consumes without naming a single one of
 * them. These tests pin the three behaviors that make that possible: configured sources merge,
 * unconfigured or failing sources simply contribute nothing, and identity questions
 * (owns/placeholder/resolver) route by each source's sentinel base URL.
 */
private class FakeNativeSource(override val baseUrl: String) : NativeLiveSource {
    override val sourceName: String = baseUrl
    override val placeholderAddon: Addon = Addon(
        id = "id-$baseUrl",
        name = baseUrl,
        version = "1.0",
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = emptyList(),
        types = emptyList(),
        resources = emptyList(),
    )
}

private class FakeLoader(
    override val isConfigured: Boolean = true,
    private val result: NativeLiveCatalogLoad = NativeLiveCatalogLoad.EMPTY,
    private val throws: Boolean = false,
) : NativeLiveCatalogLoader {
    var calls = 0
    override suspend fun load(): NativeLiveCatalogLoad {
        calls++
        if (throws) throw IllegalStateException("source down")
        return result
    }
}

private class FakeResolver : NativeLiveStreamResolver {
    override suspend fun resolve(channelId: String): LiveTvPlayableStream? = null
    override suspend fun resolveAll(channelId: String): List<LiveTvPlayableStream> = emptyList()
}

private fun channel(baseUrl: String, id: String = "c1") = LiveTvChannel(
    id = id,
    addonBaseUrl = baseUrl,
    addonName = baseUrl,
    catalogIds = setOf("1"),
    catalogName = "Todo",
    apiType = "tv",
    name = id,
    logoUrl = null,
    posterUrl = null,
    description = null,
    genres = emptyList(),
)

private fun catalog(baseUrl: String, id: String) = LiveTvCatalog(
    addonBaseUrl = baseUrl,
    addonName = baseUrl,
    addonId = "id-$baseUrl",
    catalogId = id,
    catalogName = id,
    apiType = "tv",
)

class NativeLiveSourcesTest {

    @Test
    fun `load merges every configured source and skips the unconfigured one`() = runTest {
        val a = FakeLoader(result = NativeLiveCatalogLoad(
            catalogs = listOf(catalog("a://native", "1")),
            channels = listOf(channel("a://native")),
        ))
        val b = FakeLoader(isConfigured = false)
        val sources = NativeLiveSources(listOf(
            NativeLiveSources.Entry(FakeNativeSource("a://native"), a, FakeResolver()),
            NativeLiveSources.Entry(FakeNativeSource("b://native"), b, FakeResolver()),
        ))

        val load = sources.load()

        assertEquals(1, load.catalogs.size)
        assertEquals(1, load.channels.size)
        assertEquals(0, load.failedCategories)
        assertEquals(1, a.calls)
        // An unconfigured source is absent, like an addon that is not installed: never consulted.
        assertEquals(0, b.calls)
    }

    @Test
    fun `a source whose load fails contributes nothing and does not sink the others`() = runTest {
        val dead = FakeLoader(throws = true)
        val alive = FakeLoader(result = NativeLiveCatalogLoad(
            catalogs = listOf(catalog("b://native", "1")),
            channels = listOf(channel("b://native")),
        ))
        val sources = NativeLiveSources(listOf(
            NativeLiveSources.Entry(FakeNativeSource("a://native"), dead, FakeResolver()),
            NativeLiveSources.Entry(FakeNativeSource("b://native"), alive, FakeResolver()),
        ))

        val load = sources.load()

        assertEquals(listOf("b://native"), load.catalogs.map { it.addonBaseUrl })
        assertEquals(listOf("b://native"), load.channels.map { it.addonBaseUrl })
        assertEquals(0, load.failedCategories)
    }

    @Test
    fun `identity and resolution route by each source's sentinel base url`() = runTest {
        val resolverA = FakeResolver()
        val resolverB = FakeResolver()
        val sourceA = FakeNativeSource("a://native")
        val sourceB = FakeNativeSource("b://native")
        val sources = NativeLiveSources(listOf(
            NativeLiveSources.Entry(sourceA, FakeLoader(), resolverA),
            NativeLiveSources.Entry(sourceB, FakeLoader(), resolverB),
        ))

        assertTrue(sources.ownsChannel(channel("a://native")))
        assertTrue(sources.ownsChannel(channel("b://native")))
        assertFalse(sources.ownsChannel(channel("https://addon.example")))
        assertSame(sourceA.placeholderAddon, sources.placeholderAddonFor(channel("a://native")))
        assertSame(sourceB.placeholderAddon, sources.placeholderAddonFor(channel("b://native")))
        assertNull(sources.placeholderAddonFor(channel("https://addon.example")))
        assertSame(resolverA, sources.resolverFor(channel("a://native")))
        assertSame(resolverB, sources.resolverFor(channel("b://native")))
        assertNull(sources.resolverFor(channel("https://addon.example")))
    }
}
