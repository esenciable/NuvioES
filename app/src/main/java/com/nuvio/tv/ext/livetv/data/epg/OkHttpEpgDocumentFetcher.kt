package com.nuvio.tv.ext.livetv.data.epg

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Reads XMLTV documents over HTTP.
 *
 * Takes the addon-permissive client, the same one upstream uses for addon-provided URLs: a self-hosted
 * addon may serve its guide over HTTPS with a self-signed certificate, and the validating client would
 * refuse it. It is deliberately **not** used for first-party traffic.
 *
 * ### The timeout is raised on purpose
 *
 * Addons build their guide lazily, and a cold build is genuinely slow -- the provider this was tested
 * against takes over a minute to assemble a country feed the first time. Inheriting the addon client's
 * 60 second read timeout would time out exactly when the guide is being built for the first time, which
 * looks identical to "this addon publishes no guide" while it is really "we did not wait". Subsequent
 * requests are served from the provider's cache and return immediately.
 *
 * The gzip handling is left to the parser, which sniffs the magic bytes. That covers both cases without
 * this class having to know which one it is: a `.gz` file arrives compressed, and a response OkHttp
 * already transparently inflated arrives plain.
 */
class OkHttpEpgDocumentFetcher(
    baseClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher
) : EpgDocumentFetcher {

    private val client = baseClient.newBuilder()
        .readTimeout(EPG_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun fetch(url: String): ByteArray? = withContext(ioDispatcher) {
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.bytes()
            }
        }.getOrNull()
    }

    companion object {
        const val EPG_READ_TIMEOUT_SECONDS: Long = 180L
    }
}
