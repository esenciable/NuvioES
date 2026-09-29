package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.ext.livetv.domain.LiveTvPlayableStream

/**
 * Remembers the streams already resolved for the preview.
 *
 * Walking a list with a preview turns every pause into a stream request. Resolving the same channel
 * twice because the user moved away and came back is pure waste, and the addon pays for each request
 * upstream -- it already runs a token bucket on its side for exactly this reason, which is a strong hint
 * that the client should not be the one spending the budget.
 *
 * Least-recently-used, bounded, and access-ordered: re-reading an entry counts as using it, so the
 * channels the user keeps coming back to survive while the ones they skimmed past age out.
 *
 * Synchronised because the preview resolves on a background dispatcher while a fast D-pad can ask for
 * the next entry from the main thread.
 */
class LiveTvPreviewCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    private val entries = object : LinkedHashMap<String, LiveTvPlayableStream>(
        16,
        0.75f,
        /* accessOrder = */ true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, LiveTvPlayableStream>?
        ): Boolean = size > maxEntries
    }

    @Synchronized
    fun get(channelKey: String): LiveTvPlayableStream? = entries[channelKey]

    @Synchronized
    fun put(channelKey: String, stream: LiveTvPlayableStream) {
        entries[channelKey] = stream
    }

    @Synchronized
    fun forget(channelKey: String) {
        entries.remove(channelKey)
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() {
        entries.clear()
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 12
    }
}
