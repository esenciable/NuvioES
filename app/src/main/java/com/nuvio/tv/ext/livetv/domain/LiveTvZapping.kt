package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel

/**
 * Picks the channel a zap moves to.
 *
 * Pure, and it takes the list it is given rather than reaching for one: the caller hands it the
 * **already-filtered** list, so zapping can never step onto a channel the parental filter removed or
 * outside the selected category. The reference fork's `selectNextChannel` worked the same way, and its
 * handoff to the main player worked the opposite way -- the leak that this feature removes.
 *
 * Selection is by [LiveTvChannel.stableKey], never by position, so a re-sort or a filter change cannot
 * silently move the user to a different channel.
 */
object LiveTvZapping {

    /** Moves one channel down the list. */
    fun nextKey(channels: List<LiveTvChannel>, currentKey: String?): String? =
        neighbourKey(channels, currentKey, step = 1)

    /** Moves one channel up the list. */
    fun previousKey(channels: List<LiveTvChannel>, currentKey: String?): String? =
        neighbourKey(channels, currentKey, step = -1)

    /**
     * The neighbour [step] places away, wrapping around the ends.
     *
     * Two edge cases get an explicit rule instead of falling out of the arithmetic:
     *
     * - **A current channel that is no longer in the list** (a filter change, a refresh, a search) is
     *   treated as sitting *before* the first row when moving forward and *after* the last when moving
     *   back. So "next" lands on the first channel and "previous" on the last -- the two answers a user
     *   would expect, and the same ones the fork's `if (currentIndex in list.indices) ... else 0` gave
     *   for the forward case only.
     * - **An empty list** is a no-op: there is no channel to move to.
     */
    fun neighbourKey(channels: List<LiveTvChannel>, currentKey: String?, step: Int): String? {
        if (channels.isEmpty()) return null

        val size = channels.size
        val found = channels.indexOfFirst { it.stableKey == currentKey }
        // A missing current channel has no real index, so choose the virtual one that makes the step
        // land where the rule above said it should.
        val from = when {
            found >= 0 -> found
            step > 0 -> -1
            else -> 0
        }
        val target = ((from + step) % size + size) % size
        return channels[target].stableKey
    }

    /**
     * Every channel a zap may try, in the order to try them.
     *
     * A single neighbour is not enough when channels die: a source-less channel must not strand the
     * zap, so the caller walks this sequence and plays the first key that resolves. The sequence is
     * every channel following [currentKey] in the [step] direction, wrapping at the list edges, and
     * stopping BEFORE the origin -- so each other visible channel is tried exactly once and the pass
     * is bounded by the list size by construction. The helper knows nothing about playability; it
     * only orders the attempts, and the caller decides failure after the full pass finds nothing.
     *
     * A current channel missing from the list (a filter change, a refresh, mid-zap) uses the same
     * virtual-position rule as [neighbourKey] -- before the first row going forward, after the last
     * going back -- and the pass then covers the WHOLE list. An empty list yields nothing to try.
     */
    fun zapCandidates(channels: List<LiveTvChannel>, currentKey: String?, step: Int): List<String> {
        if (channels.isEmpty()) return emptyList()

        val size = channels.size
        val found = channels.indexOfFirst { it.stableKey == currentKey }
        val from = when {
            found >= 0 -> found
            step > 0 -> -1
            else -> size
        }
        // With the origin in the list it is the one channel NOT tried; without it, the pass is full.
        val count = if (found >= 0) size - 1 else size
        return List(count) { i ->
            val target = ((from + (i + 1) * step) % size + size) % size
            channels[target].stableKey
        }
    }
}
