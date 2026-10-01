package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel

/**
 * Splits the addon's catalog output into TV channels and live sports matches.
 *
 * The owner's addon publishes live matches in the same `tv` catalog the real channels ride in, with
 * ids stamped `rb_{matchId}_{sportType}`. Because the catalog selector takes every `tv` catalog, those
 * matches were flowing into the channel list -- where they broke zapping, categories and search with
 * events that are not channels.
 *
 * Pure, and it preserves the addon's own order on both sides: for the channels that order is priority,
 * and for the matches it is live/priority order, so neither list may be re-sorted here.
 */
object LiveTvPartition {

    /** The id prefix the owner's addon stamps onto every live match event. */
    private const val MATCH_ID_PREFIX = "rb_"

    /** Whether this entry is a live match rather than a TV channel. */
    fun isMatch(channel: LiveTvChannel): Boolean = channel.id.startsWith(MATCH_ID_PREFIX)

    fun split(channels: List<LiveTvChannel>): Partition = Partition(
        channels = channels.filterNot(::isMatch),
        matches = channels.filter(::isMatch)
    )

    /**
     * The two lists the catalog actually contained. Both keep the addon's delivery order.
     */
    data class Partition(
        /** Real TV channels, safe for the channel list, zapping, categories and search. */
        val channels: List<LiveTvChannel>,
        /** Live matches, for the matches view. */
        val matches: List<LiveTvChannel>
    )
}
