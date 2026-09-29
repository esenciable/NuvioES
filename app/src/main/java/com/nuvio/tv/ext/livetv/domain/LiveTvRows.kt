package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.ext.livetv.data.epg.EpgChannelNames
import com.nuvio.tv.ext.livetv.data.epg.EpgProgram
import com.nuvio.tv.ext.livetv.data.epg.EpgSnapshot
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategory
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategoryId
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannelRow
import com.nuvio.tv.ext.livetv.domain.model.LiveTvProgramme

/**
 * Places each channel on the timeline.
 *
 * Pure, so it is fully testable without a device, a network or a clock. The reference fork resolved the
 * guide inside a UI component and memoised the result in a mutable map guarded only on its writes, so a
 * reader on another thread could observe it mid-clear.
 */
object LiveTvRows {

    fun build(
        channels: List<LiveTvChannel>,
        guide: EpgSnapshot,
        aliases: Map<String, String>,
        favorites: Set<String>,
        nowEpochMs: Long
    ): List<LiveTvChannelRow> = channels.map { channel ->
        val guideChannelId = guide.index.findGuideChannelId(channel.id, channel.name, aliases)
        val programmes = guideChannelId
            ?.let { guide.guide.programsByChannelId[it] }
            .orEmpty()

        LiveTvChannelRow(
            channel = channel,
            now = programmes.firstOrNull { it.isOnAirAt(nowEpochMs) }?.toProgramme(),
            next = programmes.firstOrNull { it.startEpochMs > nowEpochMs }?.toProgramme(),
            isFavorite = channel.stableKey in favorites,
            programmes = programmes.map { it.toProgramme() }
        )
    }

    /** The two built-in categories first, then one per addon catalog, in addon order. */
    fun categoriesFor(catalogs: List<LiveTvCatalog>): List<LiveTvCategory> = buildList {
        add(LiveTvCategory(LiveTvCategoryId.All))
        add(LiveTvCategory(LiveTvCategoryId.Favorites))
        catalogs.forEach { catalog ->
            add(LiveTvCategory(id = catalog.categoryId, addonCatalogName = catalog.catalogName))
        }
    }

    /**
     * Keys aliases the way the matcher looks them up, so a user-written alias does not depend on how
     * they capitalised or accented the channel name.
     */
    fun normaliseAliases(aliases: Map<String, String>): Map<String, String> =
        aliases.entries.associate { (name, guideChannelId) ->
            EpgChannelNames.normalize(name) to guideChannelId
        }

    private fun EpgProgram.toProgramme() = LiveTvProgramme(
        title = title,
        description = description,
        startEpochMs = startEpochMs,
        stopEpochMs = stopEpochMs
    )
}
