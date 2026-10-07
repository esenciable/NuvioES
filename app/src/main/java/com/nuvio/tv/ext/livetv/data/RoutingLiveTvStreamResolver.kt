package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.ext.livetv.domain.LiveTvPlayableStream
import com.nuvio.tv.ext.livetv.domain.LiveTvStreamResolver
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import com.nuvio.tv.ext.livetv.magis.MagisLiveSource

/**
 * Dispatches channel resolution between the addon protocol and the native Magis source.
 *
 * The ViewModel has ONE resolution path (preview, play, matches, retry all funnel through
 * [LiveTvStreamResolver]); this is where a native channel — which has no installed addon behind
 * it — silently rides the same path instead of forcing a second, parallel pipeline. Routing is by
 * the channel's sentinel base URL ([MagisLiveSource.ownsChannel]), which no real addon can carry.
 */
class RoutingLiveTvStreamResolver(
    private val addonResolver: LiveTvStreamResolver,
    private val magisResolver: MagisStreamResolver,
) : LiveTvStreamResolver {

    override suspend fun resolve(addon: Addon, channel: LiveTvChannel): LiveTvPlayableStream? =
        if (MagisLiveSource.ownsChannel(channel)) {
            magisResolver.resolve(channel.id)
        } else {
            addonResolver.resolve(addon, channel)
        }

    override suspend fun resolveAll(addon: Addon, channel: LiveTvChannel): List<LiveTvPlayableStream> =
        if (MagisLiveSource.ownsChannel(channel)) {
            magisResolver.resolveAll(channel.id)
        } else {
            addonResolver.resolveAll(addon, channel)
        }
}
