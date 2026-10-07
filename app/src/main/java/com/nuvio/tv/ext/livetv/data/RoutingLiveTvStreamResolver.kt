package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.ext.livetv.domain.LiveTvPlayableStream
import com.nuvio.tv.ext.livetv.domain.LiveTvStreamResolver
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel

/**
 * Dispatches channel resolution between the addon protocol and the injected native sources.
 *
 * The ViewModel has ONE resolution path (preview, play, matches, retry all funnel through
 * [LiveTvStreamResolver]); this is where a native channel — which has no installed addon behind
 * it — silently rides the same path instead of forcing a second, parallel pipeline. Routing is by
 * the channel's sentinel base URL ([NativeLiveSources.resolverFor]): the FIRST native source that
 * owns the channel resolves it, and a channel no native source owns falls through to the addon
 * resolver. No real addon can carry a native source's sentinel scheme.
 */
class RoutingLiveTvStreamResolver(
    private val addonResolver: LiveTvStreamResolver,
    private val native: NativeLiveSources,
) : LiveTvStreamResolver {

    override suspend fun resolve(addon: Addon, channel: LiveTvChannel): LiveTvPlayableStream? =
        native.resolverFor(channel)?.resolve(channel.id)
            ?: addonResolver.resolve(addon, channel)

    override suspend fun resolveAll(addon: Addon, channel: LiveTvChannel): List<LiveTvPlayableStream> =
        native.resolverFor(channel)?.resolveAll(channel.id)
            ?: addonResolver.resolveAll(addon, channel)
}
