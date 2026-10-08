package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.ext.livetv.domain.NativeVodSource
import com.nuvio.tv.ext.livetv.domain.NativeVodStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native-VOD list contract: names in injection order, unconfigured sources dropped entirely
 * (absent, never failed), and each source's failure isolated to that source — the remaining
 * sources still resolve and the caller never sees the exception.
 */
class NativeVodSourcesTest {

    private class FakeSource(
        override val name: String,
        override val isConfigured: Boolean = true,
        private val streams: List<NativeVodStream> = listOf(NativeVodStream("https://cdn.test/vod/1.ts")),
        private val error: Exception? = null,
    ) : NativeVodSource {
        var resolveCalls = 0
        override suspend fun resolve(type: String, videoId: String, season: Int?, episode: Int?): List<NativeVodStream> {
            resolveCalls++
            error?.let { throw it }
            return streams
        }
    }

    @Test
    fun `names keep injection order`() {
        val sources = NativeVodSources(
            listOf(FakeSource("Magis VOD"), FakeSource("Other VOD")),
        )
        assertEquals(listOf("Magis VOD", "Other VOD"), sources.names)
    }

    @Test
    fun `unconfigured sources are skipped without a resolve call`() = runTest {
        val unconfigured = FakeSource("Absent VOD", isConfigured = false)
        val configured = FakeSource("Magis VOD")
        val sources = NativeVodSources(listOf(unconfigured, configured))

        assertEquals(listOf("Magis VOD"), sources.configuredSources().map { it.name })

        val resolved = sources.resolveConfigured("movie", "tt1", null, null)
        assertEquals(0, unconfigured.resolveCalls)
        assertEquals(listOf("Magis VOD"), resolved.map { it.sourceName })
    }

    @Test
    fun `one failing source does not sink the others and never throws`() = runTest {
        val failures = mutableListOf<String>()
        val dying = FakeSource("Dying VOD", error = IllegalStateException("portal exploded"))
        val healthy = FakeSource("Magis VOD")
        val sources = NativeVodSources(listOf(dying, healthy))

        val resolved = sources.resolveConfigured("movie", "tt1", null, null) { source, _ ->
            failures += source.name
        }

        assertEquals(listOf("Dying VOD"), failures)
        assertEquals(1, dying.resolveCalls)
        assertEquals(listOf("Magis VOD"), resolved.map { it.sourceName })
        assertEquals("https://cdn.test/vod/1.ts", resolved.single().streams.single().url)
    }

    @Test
    fun `sources with no streams contribute no group`() = runTest {
        val empty = FakeSource("Empty VOD", streams = emptyList())
        val sources = NativeVodSources(listOf(empty))
        assertTrue(sources.resolveConfigured("movie", "tt1", null, null).isEmpty())
    }
}
