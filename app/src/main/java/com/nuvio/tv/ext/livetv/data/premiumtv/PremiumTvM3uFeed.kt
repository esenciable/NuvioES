package com.nuvio.tv.ext.livetv.data.premiumtv

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Reads an M3U document over HTTP. Null means the fetch failed or the answer is not usable. */
interface M3uDocumentFetcher {
    suspend fun fetch(url: String): String?
}

/**
 * The OkHttp implementation. It reuses the fork's own client (the addon-permissive one, the same
 * base the EPG fetcher builds on) and stamps a Chrome user agent on the request — the list's host
 * answers plain GitHub raw traffic, and Chrome/120 is the UA the reference plugin defaults to.
 */
class OkHttpM3uFetcher(
    baseClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher,
) : M3uDocumentFetcher {

    private val client = baseClient.newBuilder()
        .callTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun fetch(url: String): String? = withContext(ioDispatcher) {
        runCatching {
            client.newCall(
                Request.Builder().url(url).header("User-Agent", CHROME_USER_AGENT).build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.getOrNull()
    }

    companion object {
        const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val FETCH_TIMEOUT_SECONDS: Long = 60L
    }
}

/**
 * The PremiumTV list: fetch, parse and remember.
 *
 * ### TTL, not a 5-minute cache
 *
 * The list is a static 375 KB M3U that changes rarely, so a session TTL (~30 min) is plenty; a
 * forced load (the Live TV screen's manual refresh) re-consults regardless of the window.
 *
 * ### Last-known-good, never a silent empty list
 *
 * The reference plugin's defect — a dead M3U publishing an EMPTY catalog — is explicitly NOT
 * inherited: a failed fetch (or a 200 whose body parses to nothing) keeps the previous entries
 * visible and reports the failure as a count. Only a fresh, non-empty parse replaces the list.
 */
class PremiumTvM3uFeed(
    private val fetcher: M3uDocumentFetcher,
    private val url: String = LIST_URL,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {

    data class Load(
        val entries: List<M3uEntry>,
        /** Fetch failures this load: 1 means the last-known-good list (or nothing) was served. */
        val failedFetches: Int,
    ) {
        companion object {
            val EMPTY = Load(entries = emptyList(), failedFetches = 0)
        }
    }

    private var lastGood: List<M3uEntry>? = null
    private var lastGoodAtMs = 0L

    /** Whether the feed ever obtained a usable list; [load] keeps serving it after failures. */
    val hasLastKnownGood: Boolean get() = lastGood != null

    suspend fun load(force: Boolean = false): Load {
        val cached = lastGood
        if (!force && cached != null && clock() - lastGoodAtMs < ttlMs) {
            return Load(cached, failedFetches = 0)
        }

        val text = runCatching { fetcher.fetch(url) }.getOrNull()
        val entries = text?.let { M3uParser.parse(it) }.orEmpty()
        if (entries.isEmpty()) {
            // A failed fetch — or a body that parses to nothing, the same silent-empty outcome
            // wearing a success code — NEVER replaces a good list. The failure is reported as a
            // count; the previous channels stay visible.
            return cached?.let { Load(it, failedFetches = 1) } ?: Load.EMPTY.copy(failedFetches = 1)
        }
        lastGood = entries
        lastGoodAtMs = clock()
        return Load(entries, failedFetches = 0)
    }

    companion object {
        /** The verified list (2026-10-07): HTTP 200, 375 KB, 1850 channels, 46 groups. */
        const val LIST_URL = "https://raw.githubusercontent.com/JMigue85/IPTV-SV/refs/heads/main/IPTVSV.m3u"

        /** A session TTL: the list changes rarely and weighs 375 KB; the manual refresh re-consults. */
        const val DEFAULT_TTL_MS: Long = 30 * 60_000L
    }
}
