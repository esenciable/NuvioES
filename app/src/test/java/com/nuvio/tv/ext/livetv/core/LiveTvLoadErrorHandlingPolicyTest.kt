package com.nuvio.tv.ext.livetv.core

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The decision table of [LiveTvLoadErrorHandlingPolicy]: which HTTP failures a live stream retries,
 * which ones it declares fatal immediately, and which ones it hands back to media3's default policy.
 */
class LiveTvLoadErrorHandlingPolicyTest {

    private companion object {
        const val MAX_RETRIES = LiveTvLoadErrorHandlingPolicy.DEFAULT_MAX_RETRIES
    }

    @Test
    fun `a 404 retries with growing backoff inside the budget`() {
        // errorCount starts at 1, so this grants exactly MAX_RETRIES retries.
        (1..MAX_RETRIES).forEach { errorCount ->
            val delay = LiveTvLoadErrorHandlingPolicy.retryDelayMsFor(404, errorCount, MAX_RETRIES)
            assertEquals(LiveTvLoadErrorHandlingPolicy.BACKOFF_BASE_MS * errorCount, delay)
        }
    }

    @Test
    fun `any 4xx and 5xx retries the same way`() {
        listOf(400, 404, 408, 410, 416, 500, 502, 503).forEach { code ->
            val delay = LiveTvLoadErrorHandlingPolicy.retryDelayMsFor(code, 1, MAX_RETRIES)
            assertEquals(LiveTvLoadErrorHandlingPolicy.BACKOFF_BASE_MS, delay)
        }
    }

    @Test
    fun `past the retry budget the failure is fatal`() {
        val delay = LiveTvLoadErrorHandlingPolicy.retryDelayMsFor(404, MAX_RETRIES + 1, MAX_RETRIES)
        assertEquals(C.TIME_UNSET, delay)
    }

    @Test
    fun `auth rejections are fatal immediately`() {
        listOf(401, 403).forEach { code ->
            val delay = LiveTvLoadErrorHandlingPolicy.retryDelayMsFor(code, 1, MAX_RETRIES)
            assertEquals(C.TIME_UNSET, delay)
        }
    }

    @Test
    fun `non HTTP failures are delegated to the default policy`() {
        // null = not ours to decide; the wrapper falls through to media3's DefaultLoadErrorHandlingPolicy.
        assertNull(LiveTvLoadErrorHandlingPolicy.retryDelayMsFor(null, 1, MAX_RETRIES))
    }
}
