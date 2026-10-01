package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel

/** A stream that can be handed to the player. */
data class LiveTvPlayableStream(
    val url: String,
    /** The addon's own label for this source, shown while switching. */
    val name: String?,
    val headers: Map<String, String>?
)

/** Why a channel could not be opened. A code, so the UI can localise it (RF-53). */
enum class LiveTvPlayFailure {
    /** The addon answered and none of its streams carries a usable URL. */
    NO_STREAMS,

    /** The addon could not be reached, or answered with an error. */
    RESOLVE_FAILED
}

/**
 * Turns a channel into something the player can open.
 *
 * Takes the installed [Addon] rather than a base URL because that is what upstream's
 * `StreamRepository.getStreamsFromAddon` wants, and for a stated reason: the display name and logo
 * stamped onto each stream come from the manifest already held in memory, and passing a bare URL made
 * it re-fetch the manifest ahead of every stream request.
 */
interface LiveTvStreamResolver {
    suspend fun resolve(addon: Addon, channel: LiveTvChannel): LiveTvPlayableStream?

    /**
     * Every stream of the channel that carries a usable URL, in the addon's own order.
     *
     * Deliberately the addon's order and not a re-ranking: the addon already ordered its sources by
     * priority, and re-sorting them here would mean guessing what it knows. Empty (never null) when the
     * addon could not be reached or answered without usable streams, so callers can treat "nothing"
     * uniformly.
     */
    suspend fun resolveAll(addon: Addon, channel: LiveTvChannel): List<LiveTvPlayableStream>
}
