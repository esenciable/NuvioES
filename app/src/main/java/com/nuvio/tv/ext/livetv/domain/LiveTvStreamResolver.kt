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
}
