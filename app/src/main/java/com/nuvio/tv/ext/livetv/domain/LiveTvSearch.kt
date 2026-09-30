package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.ext.livetv.data.epg.EpgChannelNames
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel

/**
 * Filters channels by what the user typed.
 *
 * ### Prefix per word, not a substring of the whole name
 *
 * Every word the user typed has to match the start of **some** word of the channel name. That sounds
 * fussier than a substring search and it is the opposite: naive normalisation concatenates the words of a
 * name, so the query `"a&e"` becomes `"ae"` and cheerfully matches `"nbaeventos"`. A test caught that on
 * the first run, which is why this is word-aware.
 *
 * Prefix rather than whole word, so typing `"can"` finds `"Canal 13"` while it is still being typed.
 *
 * Accents, case and quality markers are all ignored, the same way the guide ignores them when it matches
 * channels: typing an accent on a television remote is miserable, so `"television espanola"` has to find
 * `"Televisión Española"` and `"canal 13"` has to find `"Canal 13 FHD"`.
 *
 * A blank query returns the list untouched, so callers do not need to branch.
 */
fun List<LiveTvChannel>.matching(query: String): List<LiveTvChannel> {
    val needles = EpgChannelNames.searchTokens(query)
    if (needles.isEmpty()) return this

    return filter { channel ->
        val haystack = EpgChannelNames.searchTokens(channel.name)
        needles.all { needle -> haystack.any { word -> word.startsWith(needle) } }
    }
}
