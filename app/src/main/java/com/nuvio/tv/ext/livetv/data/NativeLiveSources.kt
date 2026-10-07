package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.ext.livetv.domain.LiveTvCatalog
import com.nuvio.tv.ext.livetv.domain.LiveTvPlayableStream
import com.nuvio.tv.ext.livetv.domain.NativeLiveSource
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel

/**
 * What one native source's catalog walk produces, expressed in the fork's Live TV models. Shared by
 * every native source so the ViewModel can merge a LIST of them without knowing their names.
 */
data class NativeLiveCatalogLoad(
    val catalogs: List<LiveTvCatalog> = emptyList(),
    val channels: List<LiveTvChannel> = emptyList(),
    /** Categories whose channel reads failed; their catalog chip still publishes, minus channels. */
    val failedCategories: Int = 0,
) {
    companion object {
        val EMPTY = NativeLiveCatalogLoad()
    }
}

/**
 * The catalog side of one native source. Implemented per source (e.g. [MagisChannelLoader]); the
 * disabled-config contract is carried here: [isConfigured] false means the source is ABSENT — never
 * a crash, never a load.
 */
interface NativeLiveCatalogLoader {
    val isConfigured: Boolean
    suspend fun load(): NativeLiveCatalogLoad
}

/**
 * The playback side of one native source: resolution by the source's own channel id. The routing
 * resolver calls it for channels the source owns and never asks what protocol stands behind it.
 */
interface NativeLiveStreamResolver {
    suspend fun resolve(channelId: String): LiveTvPlayableStream?
    suspend fun resolveAll(channelId: String): List<LiveTvPlayableStream>
}

/**
 * The list of native live sources, injected as ONE dependency the ViewModel iterates without naming
 * any of them: catalog load + merge, the placeholder-addon lookup, the preview-cache bypass and the
 * category slider all run over [entries].
 *
 * Each source must keep its sentinel [NativeLiveSource.baseUrl] unique — that is what makes
 * [entryFor] unambiguous and the two keyspaces collision-free (each source's channels key off its
 * own sentinel, no real addon can carry those schemes).
 */
class NativeLiveSources(private val entries: List<Entry>) {

    /** One native source: its identity plus the two adapters the ViewModel and the resolver use. */
    data class Entry(
        val source: NativeLiveSource,
        val catalogLoader: NativeLiveCatalogLoader,
        val streamResolver: NativeLiveStreamResolver,
    )

    /** Whether [channel] plays through ANY native source rather than an installed addon. */
    fun ownsChannel(channel: LiveTvChannel): Boolean = entries.any { it.source.ownsChannel(channel) }

    /**
     * The owning source's placeholder addon, or null when [channel] is not native — the addon
     * lookup case, the classic unresolvable channel.
     */
    fun placeholderAddonFor(channel: LiveTvChannel): Addon? =
        entryFor(channel)?.source?.placeholderAddon

    /** The resolver of the source that owns [channel], or null for a non-native channel. */
    fun resolverFor(channel: LiveTvChannel): NativeLiveStreamResolver? = entryFor(channel)?.streamResolver

    /**
     * Loads and merges every CONFIGURED source. An unconfigured source is skipped entirely, and a
     * source whose load fails contributes nothing rather than sinking the rest — the disabled-config
     * path (blank MAGIS_3DES_KEY and friends) keeps every other source, and the failure stays
     * invisible at the source level exactly like a source that is not installed.
     */
    suspend fun load(): NativeLiveCatalogLoad {
        var catalogs = emptyList<LiveTvCatalog>()
        var channels = emptyList<LiveTvChannel>()
        var failedCategories = 0
        for (entry in entries) {
            val loader = entry.catalogLoader
            if (!loader.isConfigured) continue
            val load = runCatching { loader.load() }.getOrDefault(NativeLiveCatalogLoad.EMPTY)
            catalogs += load.catalogs
            channels += load.channels
            failedCategories += load.failedCategories
        }
        return NativeLiveCatalogLoad(catalogs = catalogs, channels = channels, failedCategories = failedCategories)
    }

    private fun entryFor(channel: LiveTvChannel): Entry? = entries.firstOrNull { it.source.ownsChannel(channel) }
}
