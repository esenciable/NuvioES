package com.nuvio.tv.ext.livetv.data.epg

import java.text.Normalizer

/**
 * Normalises channel names so that the same channel written two ways meets in the middle:
 * `"Canal 13 de Argentina HD"` and `"CANAL 13"` both become `"canal13"`.
 *
 * Normalisation is aggressive on purpose -- case, accents, punctuation and quality markers all go --
 * because the comparison on top of it is **exact**. Being lenient here is what lets the match stay
 * strict: there is no substring fallback anywhere.
 */
object EpgChannelNames {

    /** Markers that describe a feed, not a channel, so they must not distinguish two names. */
    private val QUALITY_MARKERS = setOf(
        "hd", "fhd", "uhd", "sd", "hdtv", "fullhd",
        "4k", "8k", "1080p", "720p", "480p", "2160p"
    )

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val SEPARATORS = Regex("[^a-z0-9]+")

    fun normalize(raw: String): String {
        val lowered = raw.trim().lowercase()
        val withoutAccents = Normalizer.normalize(lowered, Normalizer.Form.NFD).replace(DIACRITICS, "")
        return withoutAccents
            .split(SEPARATORS)
            .filter { it.isNotEmpty() && it !in QUALITY_MARKERS }
            .joinToString(separator = "")
    }
}
