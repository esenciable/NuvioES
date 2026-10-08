package com.nuvio.tv.ext.livetv.magis

import com.nuvio.tv.ext.livetv.domain.NativeVodSource
import com.nuvio.tv.ext.livetv.domain.NativeVodStream

/**
 * The Magis VOD client as ONE entry of the [com.nuvio.tv.ext.livetv.data.NativeVodSources] list
 * injected into the stream pipeline — the VOD mirror of [MagisLiveSource].
 *
 * A build without a usable Magis configuration gets `client = null`: [isConfigured] is then
 * false and the source is skipped before any portal call — the same disabled-config contract
 * the live gateway keeps ("source absent, never a crash"). The client reads the runtime config
 * PER USE, so host/key rotations land without a restart while the process lives.
 */
internal class MagisVodSource(
    override val name: String = DEFAULT_SOURCE_NAME,
    private val client: MagisVodClient?,
) : NativeVodSource {

    override val isConfigured: Boolean
        get() = client != null

    override suspend fun resolve(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
    ): List<NativeVodStream> {
        val vodClient = client ?: return emptyList()
        // resolve() already collapses every typed failure to null; null → an empty group, so a
        // failed Magis resolve is logged by the client's own error path and skipped here.
        val stream = vodClient.resolve(
            id = videoId,
            mediaType = type,
            season = season ?: 0,
            episode = episode ?: 0,
        ) ?: return emptyList()
        return listOf(NativeVodStream(url = stream.url, headers = stream.headers))
    }

    internal companion object {
        /** The group name the native Magis VOD streams wear on the stream screen. */
        const val DEFAULT_SOURCE_NAME = "Magis VOD"
    }
}
