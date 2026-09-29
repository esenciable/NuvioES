package com.nuvio.tv.ext.livetv.data.epg

/**
 * Looks up which channel of a guide belongs to one of our channels.
 *
 * ### Three tiers, and no substring matching
 *
 * 1. **Exact id.** The addon's own guide is generated from the very channels being listed, so its
 *    `<channel id>` values match ours. When that hits, the match is exact and free of judgement.
 * 2. **User alias.** An explicit mapping the user wrote, which always overrides a guess.
 * 3. **Unique normalised display name.** Third-party guides (the country feeds) carry their own ids,
 *    so the only bridge is the name.
 *
 * The reference fork fell back to substring matching in either direction with first-match-wins over
 * an unordered map, so `Globo` could bind to `Globonews` or `Globoplay` and the user would see another
 * channel's programming with no indication anything was wrong. That fallback does not exist here.
 *
 * ### Ambiguity is refused, not resolved
 *
 * If two different guide channels normalise to the same name, that name is poisoned and never matches.
 * Picking one would be guessing, and a wrong guide is worse than no guide.
 */
class EpgGuideIndex private constructor(
    private val channelIds: Set<String>,
    private val idByNormalizedName: Map<String, String>,
    private val ambiguousNames: Set<String>
) {

    val channelCount: Int get() = channelIds.size

    val isEmpty: Boolean get() = channelIds.isEmpty()

    fun findGuideChannelId(
        channelId: String,
        channelName: String,
        aliases: Map<String, String> = emptyMap()
    ): String? {
        if (channelId in channelIds) return channelId

        val key = EpgChannelNames.normalize(channelName)
        if (key.isEmpty()) return null

        aliases[key]?.let { aliased -> if (aliased in channelIds) return aliased }

        if (key in ambiguousNames) return null
        return idByNormalizedName[key]
    }

    companion object {

        val EMPTY = EpgGuideIndex(emptySet(), emptyMap(), emptySet())

        fun of(channels: List<EpgChannel>): EpgGuideIndex {
            val ids = channels.mapTo(LinkedHashSet()) { it.id }
            val byName = LinkedHashMap<String, String>()
            val ambiguous = LinkedHashSet<String>()

            for (channel in channels) {
                for (displayName in channel.displayNames) {
                    val key = EpgChannelNames.normalize(displayName)
                    if (key.isEmpty() || key in ambiguous) continue
                    when (val existing = byName[key]) {
                        null -> byName[key] = channel.id
                        channel.id -> Unit
                        else -> {
                            ambiguous += key
                            byName.remove(key)
                        }
                    }
                }
            }
            return EpgGuideIndex(ids, byName, ambiguous)
        }
    }
}

/**
 * The programmes of one channel, resolved through [EpgGuideIndex].
 *
 * Returns an empty list rather than null so callers can render an honest "no guide" state instead of
 * branching on absence at every use.
 */
fun XmlTvGuide.programsFor(
    index: EpgGuideIndex,
    channelId: String,
    channelName: String,
    aliases: Map<String, String> = emptyMap()
): List<EpgProgram> {
    val guideChannelId = index.findGuideChannelId(channelId, channelName, aliases) ?: return emptyList()
    return programsByChannelId[guideChannelId].orEmpty()
}
