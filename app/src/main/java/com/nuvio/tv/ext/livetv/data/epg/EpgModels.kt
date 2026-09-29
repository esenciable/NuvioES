package com.nuvio.tv.ext.livetv.data.epg

/**
 * One entry of the programme guide.
 *
 * Times are epoch milliseconds, already normalised from the XMLTV `YYYYMMDDHHMMSS +ZZZZ` form.
 */
data class EpgProgram(
    val channelId: String,
    val title: String,
    val description: String?,
    val startEpochMs: Long,
    val stopEpochMs: Long
) {
    fun isOnAirAt(epochMs: Long): Boolean = epochMs >= startEpochMs && epochMs < stopEpochMs

    val durationMs: Long get() = (stopEpochMs - startEpochMs).coerceAtLeast(0L)
}

/**
 * One channel as declared by the guide. The [id] is the guide's own identifier, which in general
 * has nothing to do with our channel ids -- matching happens by display name (see
 * [com.nuvio.tv.ext.livetv.domain.EpgChannelMatcher]).
 */
data class EpgChannel(
    val id: String,
    val displayNames: List<String>,
    val iconUrl: String?
) {
    val primaryName: String? get() = displayNames.firstOrNull()
}

/**
 * Result of parsing one XMLTV document.
 *
 * [programsSkippedOutOfWindow] is reported instead of hidden: a guide that silently drops most of a
 * document looks identical to a guide that carries no programming at all.
 */
data class XmlTvGuide(
    val channels: List<EpgChannel>,
    val programsByChannelId: Map<String, List<EpgProgram>>,
    val totalProgramsParsed: Int,
    val programsSkippedOutOfWindow: Int
) {
    companion object {
        val EMPTY = XmlTvGuide(emptyList(), emptyMap(), 0, 0)
    }
}
