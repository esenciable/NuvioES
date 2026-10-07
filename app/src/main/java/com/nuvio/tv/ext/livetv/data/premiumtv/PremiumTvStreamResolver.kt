package com.nuvio.tv.ext.livetv.data.premiumtv

import com.nuvio.tv.ext.livetv.data.NativeLiveStreamResolver
import com.nuvio.tv.ext.livetv.domain.LiveTvPlayableStream
import com.nuvio.tv.ext.livetv.premiumtv.PremiumTvLiveSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The playback side of the native PremiumTV source.
 *
 * ### One stream per channel, headers chosen from the ENTRY
 *
 * The list declares per-channel headers with `#EXTVLCOPT` lines (55 entries carry a user agent, 52
 * a referrer — e.g. the TCS channels of El Salvador validating `https://teleon.tv/`), and the
 * reference plugin ignored them. The precedence here is exact and measured:
 *
 * 1. **User-Agent**: the entry's own `#EXTVLCOPT:http-user-agent`, else the list's de-facto default
 *    Chrome/120.
 * 2. **Referer**: the entry's own `#EXTVLCOPT:http-referrer`, else — ONLY when the entry declares
 *    none AND the host is `jmp2.uk` or a Samsung host — `https://www.samsung.com/`, because the 31
 *    `jmp2.uk` links are Samsung TV Plus streams (`stvp-…`) that declare no referrer of their own.
 *    That scoped default is what the plugin's GLOBAL referrer hack approximated; copying it to every
 *    channel can break the ones that validate their own referrer, so it never leaves Samsung hosts.
 *
 * ### Redirects for the shortener links
 *
 * The `jmp2.uk` links are shorteners: the real stream lives behind a redirect. For those entries
 * only, resolution probes the URL through OkHttp with redirects enabled and carries the FINAL url
 * into the stream; a non-shortener entry is never probed and its URL goes to the player untouched.
 * A probe that fails for any reason degrades to the original URL — resolution itself never fails
 * because of the probe.
 *
 * ### Resolution against the CURRENT document
 *
 * The channel id is the short hash of the stream URL ([PremiumTvLiveSource.channelIdFor]); the
 * resolver rebuilds its id -> entry index from the feed's current document on every resolve, so a
 * refreshed list is honoured immediately and an id that is no longer in the document resolves to
 * NOTHING — never a crash, never a stale stream replayed.
 */
class PremiumTvStreamResolver(
    /** The M3U feed, owner of the TTL and the last-known-good retention. */
    private val feed: PremiumTvM3uFeed,
    private val ioDispatcher: CoroutineDispatcher,
    /**
     * The client the redirect probe rides; built from the fork's addon-permissive client with a
     * short call timeout — the probe only needs to observe the redirect chain, not stream data.
     */
    baseClient: OkHttpClient = OkHttpClient(),
    /** Hosts treated as shorteners and probed for their redirect target. */
    private val shortenerHosts: Set<String> = DEFAULT_SHORTENER_HOSTS,
) : NativeLiveStreamResolver {

    private val redirectClient = baseClient.newBuilder()
        .callTimeout(REDIRECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** The primary stream of the channel, or null when the id is not in the current document. */
    override suspend fun resolve(channelId: String): LiveTvPlayableStream? =
        resolveAll(channelId).firstOrNull()

    override suspend fun resolveAll(channelId: String): List<LiveTvPlayableStream> {
        val entry = feed.load().entries.firstOrNull { PremiumTvLiveSource.channelIdFor(it.url) == channelId }
            ?: return emptyList()

        val headers = headersFor(entry)
        val url = withContext(ioDispatcher) { followRedirects(entry, headers) }
        return listOf(
            LiveTvPlayableStream(
                url = url,
                name = PremiumTvLiveSource.SOURCE_NAME,
                headers = headers,
            ),
        )
    }

    /**
     * The headers one entry plays with, by the documented precedence: the entry's own declarations
     * win; Chrome/120 is the UA fallback and the Samsung referrer exists ONLY for undeclared
     * Samsung-family hosts.
     */
    private fun headersFor(entry: M3uEntry): Map<String, String> {
        val headers = linkedMapOf("User-Agent" to (entry.userAgent ?: DEFAULT_USER_AGENT))
        val referrer = entry.referrer ?: samsungReferrerFor(entry.url)
        if (referrer != null) headers["Referer"] = referrer
        return headers
    }

    /** The Samsung referrer, or null when [url] is not a Samsung-family host. */
    private fun samsungReferrerFor(url: String): String? {
        val host = url.toHttpUrlOrNull()?.host ?: return null
        return if (isSamsungHost(host)) SAMSUNG_REFERRER else null
    }

    /**
     * The final URL after the shortener's redirects, or the original URL when the entry is not a
     * shortener or the probe fails for any reason.
     */
    private fun followRedirects(entry: M3uEntry, headers: Map<String, String>): String {
        val host = entry.url.toHttpUrlOrNull()?.host
        if (host == null || shortenerHosts.none { host == it || host.endsWith(".$it") }) return entry.url
        return runCatching {
            val request = Request.Builder().url(entry.url).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            redirectClient.newCall(request).execute().use { response ->
                response.request.url.toString()
            }
        }.getOrNull() ?: entry.url
    }

    private fun isSamsungHost(host: String): Boolean =
        host == SAMSUNG_HOST || host.endsWith(".$SAMSUNG_HOST") || host.contains("samsung")

    companion object {
        /** The list's de-facto default UA: the entries that declare none expect Chrome/120. */
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** The referrer ONLY the undeclared Samsung-family entries get. */
        const val SAMSUNG_REFERRER = "https://www.samsung.com/"

        /** The shortener the list's Samsung TV Plus entries (`stvp-…`) are published through. */
        const val SAMSUNG_HOST = "jmp2.uk"

        /** The probe only observes the redirect chain; it never streams the channel. */
        const val REDIRECT_TIMEOUT_SECONDS: Long = 15L

        val DEFAULT_SHORTENER_HOSTS: Set<String> = setOf(SAMSUNG_HOST)
    }
}
