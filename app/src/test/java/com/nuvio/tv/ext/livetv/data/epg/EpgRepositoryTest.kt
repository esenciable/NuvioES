package com.nuvio.tv.ext.livetv.data.epg

import com.nuvio.tv.ext.livetv.domain.model.EpgSource
import com.nuvio.tv.ext.livetv.domain.model.EpgSourceOrigin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
class EpgRepositoryTest {

    private var now = 1_000_000L

    @Test
    fun `a successful sync publishes the guide and its index`() = runTest {
        val repository = repository(fetcher = fetcherOf(SOURCE_URL to document("Canal 13")))

        val result = repository.sync(listOf(source()))

        assertTrue(result is EpgSyncResult.Success)
        val state = repository.state.value
        assertEquals(1, state.snapshot.guide.channels.size)
        assertEquals(1, state.snapshot.index.channelCount)
        assertEquals(listOf(SOURCE_ID), state.snapshot.sourceIds)
        assertEquals(0, state.sourcesFailed)
        assertTrue(!state.syncing)
        assertNotNull(state.snapshot.index.findGuideChannelId("x", "Canal 13"))
    }

    @Test
    fun `a failed run on the same instance leaves the published guide untouched`() = runTest {
        // The defect this pins: the reference fork cleared every cache BEFORE downloading, so a run
        // where all sources failed threw away a working guide, emptied the UI, and still recorded the
        // sync as successful.
        val documents = mutableMapOf<String, ByteArray?>(SOURCE_URL to document("Canal 13"))
        // The same map instance, so mutating it below actually changes what the fetcher returns. The
        // vararg helper copies, and a copy made this test silently pass a successful second sync.
        val repository = repository(fetcher = RecordingFetcher(documents))
        repository.sync(listOf(source()))
        val published = repository.state.value.snapshot
        assertEquals(1, published.guide.channels.size)

        documents[SOURCE_URL] = null
        now += TTL + 1
        val result = repository.sync(listOf(source()))

        assertTrue(result is EpgSyncResult.Failed)
        assertSame("the previous guide must survive a total failure", published, repository.state.value.snapshot)
        assertEquals(EpgFailureReason.DOWNLOAD_FAILED, repository.state.value.lastFailure?.reason)
    }

    @Test
    fun `concurrent syncs download each source once`() = runTest {
        val fetcher = SlowFetcher(mapOf(SOURCE_URL to document("Canal 13")))
        val repository = repository(fetcher)
        val source = source()

        val results = listOf(
            async { repository.sync(listOf(source)) },
            async { repository.sync(listOf(source)) },
            async { repository.sync(listOf(source)) }
        ).awaitAll()

        assertEquals(3, results.size)
        assertEquals("three callers must share one download", 1, fetcher.calls.size)
    }

    @Test
    fun `a cached source is not downloaded again within its ttl`() = runTest {
        val fetcher = fetcherOf(SOURCE_URL to document("Canal 13"))
        val repository = repository(fetcher)

        repository.sync(listOf(source()))
        now += TTL / 2
        repository.sync(listOf(source()))

        assertEquals(1, fetcher.calls.size)
    }

    @Test
    fun `a cached source expires after its ttl`() = runTest {
        val fetcher = fetcherOf(SOURCE_URL to document("Canal 13"))
        val repository = repository(fetcher)

        repository.sync(listOf(source()))
        now += TTL + 1
        repository.sync(listOf(source()))

        assertEquals(2, fetcher.calls.size)
    }

    @Test
    fun `forceRefresh bypasses the cache`() = runTest {
        val fetcher = fetcherOf(SOURCE_URL to document("Canal 13"))
        val repository = repository(fetcher)

        repository.sync(listOf(source()))
        repository.sync(listOf(source()), forceRefresh = true)

        assertEquals(2, fetcher.calls.size)
    }

    @Test
    fun `one broken source does not discard the others`() = runTest {
        val other = source(id = "other", url = OTHER_URL)
        val repository = repository(
            fetcher = fetcherOf(
                SOURCE_URL to document("Canal 13"),
                OTHER_URL to null
            )
        )

        val result = repository.sync(listOf(source(), other))

        assertTrue(result is EpgSyncResult.Success)
        assertEquals(1, (result as EpgSyncResult.Success).sourcesFailed)
        assertEquals(1, repository.state.value.snapshot.guide.channels.size)
        assertEquals(listOf(SOURCE_ID), repository.state.value.snapshot.sourceIds)
        assertEquals("the failure is still reported", EpgFailureReason.DOWNLOAD_FAILED, repository.state.value.lastFailure?.reason)
    }

    @Test
    fun `a source over the size limit is reported without killing the others`() = runTest {
        val other = source(id = "other", url = OTHER_URL)
        val repository = repository(
            fetcher = fetcherOf(
                SOURCE_URL to document("Canal 13"),
                OTHER_URL to document("Otro canal")
            ),
            maxBytes = 64
        )

        val result = repository.sync(listOf(source(), other))

        // Both blow the tiny cap, so nothing parses and the guide stays empty -- but the failure is
        // reported rather than swallowed.
        assertTrue(result is EpgSyncResult.Failed)
        assertEquals(EpgFailureReason.TOO_LARGE, repository.state.value.lastFailure?.reason)
    }

    @Test
    fun `a source that parses to nothing is not recorded as used`() = runTest {
        val other = source(id = "other", url = OTHER_URL)
        val repository = repository(
            fetcher = fetcherOf(
                SOURCE_URL to document("Canal 13"),
                OTHER_URL to "<tv></tv>".toByteArray()
            )
        )

        repository.sync(listOf(source(), other))

        assertEquals(listOf(SOURCE_ID), repository.state.value.snapshot.sourceIds)
    }

    private fun TestScope.repository(
        fetcher: EpgDocumentFetcher,
        maxBytes: Long = XmlTvParser.DEFAULT_MAX_DECOMPRESSED_BYTES
    ): EpgRepository {
        val dir = Files.createTempDirectory("epg-test").toFile()
        dir.deleteOnExit()
        return EpgRepository(
            fetcher = fetcher,
            cache = EpgDiskCache(root = dir, ttlMs = TTL),
            scope = backgroundScope,
            worker = StandardTestDispatcher(testScheduler),
            clock = { now },
            maxDecompressedBytes = maxBytes
        )
    }

    private class RecordingFetcher(private val responses: Map<String, ByteArray?>) : EpgDocumentFetcher {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun fetch(url: String): ByteArray? {
            calls.add(url)
            return responses[url]
        }
    }

    private class SlowFetcher(private val responses: Map<String, ByteArray?>) : EpgDocumentFetcher {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun fetch(url: String): ByteArray? {
            calls.add(url)
            delay(50)
            return responses[url]
        }
    }

    private fun fetcherOf(vararg responses: Pair<String, ByteArray?>) = RecordingFetcher(responses.toMap())

    private fun fetcherOf(responses: Map<String, ByteArray?>) = RecordingFetcher(responses)

    private fun source(id: String = SOURCE_ID, url: String = SOURCE_URL) =
        EpgSource(id = id, name = id, url = url, origin = EpgSourceOrigin.BuiltIn)

    private fun document(name: String): ByteArray = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<!DOCTYPE tv SYSTEM \"xmltv.dtd\">\n")
        append("<tv>\n")
        append("  <channel id=\"$name\"><display-name lang=\"es\">$name</display-name></channel>\n")
        append("  <programme start=\"${at(0)}\" stop=\"${at(HOUR)}\" channel=\"$name\">")
        append("<title lang=\"es\">Programa de $name</title></programme>\n")
        append("</tv>\n")
    }.toByteArray(Charsets.UTF_8)

    private fun at(offsetMs: Long): String {
        val base = java.time.OffsetDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(now + offsetMs),
            java.time.ZoneOffset.UTC
        )
        return base.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z"))
    }

    private companion object {
        const val SOURCE_ID = "addon:https://addon.test/token"
        const val SOURCE_URL = "https://addon.test/token/epg.xml"
        const val OTHER_URL = "https://other.test/epg.xml"
        const val TTL = 24L * 60 * 60 * 1000
        const val HOUR = 60L * 60L * 1000L
    }
}
