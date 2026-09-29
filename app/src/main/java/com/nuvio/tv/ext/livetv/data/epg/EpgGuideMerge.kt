package com.nuvio.tv.ext.livetv.data.epg

/**
 * Combines the guides of several sources into one.
 *
 * Several sources routinely describe the same channel, so the merge has to be idempotent rather than
 * additive: channels are de-duplicated by id, and programmes by (start, stop, title). Without the
 * second rule, loading the addon's guide plus a country feed that covers the same channel would
 * double every row of the grid.
 */
object EpgGuideMerge {

    fun merge(guides: List<XmlTvGuide>): XmlTvGuide {
        if (guides.isEmpty()) return XmlTvGuide.EMPTY

        val channels = LinkedHashMap<String, EpgChannel>()
        val programs = LinkedHashMap<String, MutableList<EpgProgram>>()

        for (guide in guides) {
            for (channel in guide.channels) {
                val existing = channels[channel.id]
                channels[channel.id] = when {
                    existing == null -> channel
                    // Keep the richest spelling of a channel already seen.
                    channel.iconUrl != null && existing.iconUrl == null ->
                        existing.copy(iconUrl = channel.iconUrl, displayNames = (existing.displayNames + channel.displayNames).distinct())
                    else -> existing.copy(displayNames = (existing.displayNames + channel.displayNames).distinct())
                }
            }
            for ((channelId, channelPrograms) in guide.programsByChannelId) {
                programs.getOrPut(channelId) { mutableListOf() }.addAll(channelPrograms)
            }
        }

        return XmlTvGuide(
            channels = channels.values.toList(),
            programsByChannelId = programs.mapValues { (_, channelPrograms) ->
                channelPrograms
                    .distinctBy { Triple(it.startEpochMs, it.stopEpochMs, it.title) }
                    .sortedBy(EpgProgram::startEpochMs)
            },
            totalProgramsParsed = guides.sumOf(XmlTvGuide::totalProgramsParsed),
            programsSkippedOutOfWindow = guides.sumOf(XmlTvGuide::programsSkippedOutOfWindow)
        )
    }
}
