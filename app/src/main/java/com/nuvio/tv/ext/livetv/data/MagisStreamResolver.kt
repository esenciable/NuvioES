package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.ext.livetv.domain.LiveTvPlayableStream
import com.nuvio.tv.ext.livetv.magis.MagisChannelSession
import com.nuvio.tv.ext.livetv.magis.MagisLivePlaybackApi
import com.nuvio.tv.ext.livetv.magis.MagisResult

/**
 * The playback side of the native Magis source.
 *
 * The port — [MagisLivePlaybackApi] — is the slice of [com.nuvio.tv.ext.livetv.magis.MagisLiveClient]
 * playback needs: resolution, the playlist URL, and the signed headers. Tests fake the portal and
 * still observe exactly what the player would be handed.
 *
 * ### One stream per CDN, in portal order
 *
 * A resolution returns every servable cfl CDN, and the resolver turns EACH into a
 * [LiveTvPlayableStream] carrying that CDN's own playlist host and its own signed `Content-Auth`.
 * The first entry plays; the rest feed the existing source picker and the fullscreen player's
 * auto-advance, which is the fork's own fallback authority — a dead first CDN moves to the second
 * without a re-resolve.
 *
 * ### Fresh signatures, stale-cache discipline
 *
 * Headers are signed at RESOLVE time and are only good for a short window (the addon re-signs per
 * request). That is accepted here because resolution happens immediately before playback, and the
 * failure mode is covered one level up: 401/403 are deliberately fatal in
 * `LiveTvLoadErrorHandlingPolicy` precisely so a signature/session expiry lands in the error
 * overlay, whose Reintentar re-resolves — a fresh session, a fresh host and a fresh signature.
 * Anything that caches a resolved Magis stream (the preview cache) must not replay it later; the
 * ViewModel keeps native channels out of that cache for exactly this reason.
 */
class MagisStreamResolver(
    /** The portal API, or null on a build without Magis configuration; resolves nothing then. */
    private val client: MagisLivePlaybackApi?,
) : NativeLiveStreamResolver {

    /** The primary stream of the channel — the first CDN in portal order — or null on failure. */
    override suspend fun resolve(channelCode: String): LiveTvPlayableStream? =
        resolveAll(channelCode).firstOrNull()

    override suspend fun resolveAll(channelCode: String): List<LiveTvPlayableStream> {
        val api = client ?: return emptyList()
        val result = runCatching { api.resolveDetailed(channelCode) }.getOrNull()
        val session = (result as? MagisResult.Ok)?.data ?: return emptyList()

        return session.cdns.mapIndexed { index, cdn ->
            // The session's top-level host/authBase are the FIRST CDN's; a fallback stream is the
            // SAME session (same playCode, same license — they must never be crossed) re-aimed at
            // one CDN's host and signed with that CDN's own authBase.
            val forCdn = session.copy(host = cdn.host, authBase = cdn.authBase)
            LiveTvPlayableStream(
                url = api.playlistUrl(forCdn),
                name = "CDN ${index + 1}",
                headers = api.signedHeaders(forCdn),
            )
        }
    }
}
