package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.ext.livetv.domain.LiveTvPlayableStream
import com.nuvio.tv.ext.livetv.domain.LiveTvStreamResolver
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel

/**
 * Resolves a channel through upstream's [StreamRepository].
 *
 * All the fetching is upstream's, including the URL shape (`/stream/{type}/{id}.json`), the path
 * encoding and the preservation of the addon's query string.
 *
 * Takes the **first** stream that carries a usable URL. Ordering is the addon's own, and it is
 * deliberately the whole ordering rather than a search for the "best" one: ranking live sources
 * requires knowing something about them, and the addon already did that. [resolveAll] now hands back
 * every usable stream in that same order, so the UI can offer source switching without this class
 * pretending to know which one is best.
 */
class AddonStreamResolver(
    private val streamRepository: StreamRepository
) : LiveTvStreamResolver {

    override suspend fun resolve(addon: Addon, channel: LiveTvChannel): LiveTvPlayableStream? {
        // The first usable entry of resolveAll() IS the single answer: the addon's own ordering already
        // decides which source plays, and keeping one filtering path means the two methods can never
        // disagree about which stream that is.
        return resolveAll(addon, channel).firstOrNull()
    }

    override suspend fun resolveAll(addon: Addon, channel: LiveTvChannel): List<LiveTvPlayableStream> {
        val result = streamRepository.getStreamsFromAddon(
            addon = addon,
            type = channel.apiType,
            videoId = channel.id
        )
        val streams = (result as? NetworkResult.Success)?.data ?: return emptyList()

        return streams.mapNotNull { stream ->
            val url = stream.getStreamUrl()
            if (url.isNullOrBlank()) return@mapNotNull null

            LiveTvPlayableStream(
                url = url,
                name = sourceLabel(stream),
                headers = stream.behaviorHints?.proxyHeaders?.request?.takeIf { it.isNotEmpty() }
            )
        }
    }
}

/**
 * The label the source picker offers for one stream, or null when nothing identifiable remains and
 * the picker falls back to its localized "Fuente N".
 *
 * The addon's sports streams carry `name = "Esencial Sport"` -- the BRAND, identical on every
 * source -- and `title = "<match title> · <real source name>"`. Offering the brand would give the
 * picker four indistinguishable rows, which the owner explicitly complained about, so when the
 * title carries the "·" separator the label is the LAST non-blank segment: the source. Without the
 * separator there is nothing to split, and the plain display name is the best there is.
 */
internal fun sourceLabel(stream: Stream): String? {
    val title = stream.title
    if (title != null && title.contains(SEPARATOR)) {
        return title.split(SEPARATOR)
            .lastOrNull { it.isNotBlank() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
    return stream.getDisplayNameOrNull()?.takeIf { it.isNotBlank() }
}

/** The separator the owner's addon uses between the match title and the real source name. */
private const val SEPARATOR = "·"
