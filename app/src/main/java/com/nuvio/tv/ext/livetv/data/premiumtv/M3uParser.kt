package com.nuvio.tv.ext.livetv.data.premiumtv

/**
 * One parsed M3U entry: the channel metadata the `#EXTINF` line declares plus the URL line that
 * follows it, with the `#EXTVLCOPT` headers THAT entry declared — the per-entry headers the
 * reference plugin ignored (55 entries carry a UA, 52 a referrer, e.g. the TCS channels of El
 * Salvador validating `https://teleon.tv/`).
 *
 * [groupTitle] is never blank: an entry without `group-title` lands in [M3uParser.FALLBACK_GROUP],
 * so grouping never loses a channel.
 */
data class M3uEntry(
    val name: String,
    val logoUrl: String?,
    val groupTitle: String,
    val url: String,
    val userAgent: String?,
    val referrer: String?,
)

/**
 * A PURE M3U parser: text in, entries out, no network, no state.
 *
 * It reads exactly the three things the PremiumTV list carries — `#EXTINF` (name, `tvg-logo`,
 * `group-title`), the `#EXTVLCOPT:http-user-agent=` / `#EXTVLCOPT:http-referrer=` lines belonging to
 * the pending entry, and the URL line that closes the entry. Everything else (`#EXTM3U`, comments,
 * blank lines) is ignored; an `#EXTINF` never followed by a URL and an `#EXTVLCOPT` before any
 * `#EXTINF` contribute nothing.
 */
object M3uParser {

    /** The synthetic group an entry without `group-title` lands in. */
    const val FALLBACK_GROUP = "Sin categoría"

    private const val UA_PREFIX = "#EXTVLCOPT:http-user-agent="
    private const val REFERRER_PREFIX = "#EXTVLCOPT:http-referrer="

    /**
     * `name="value"` (quoted — the common case, and the only one that survives commas) or
     * `name=value` (unquoted, up to the next whitespace).
     */
    private val attribute = Regex("([A-Za-z0-9_-]+)\\s*=\\s*(?:\"([^\"]*)\"|([^\\s\",]+))")

    fun parse(text: String): List<M3uEntry> {
        val entries = mutableListOf<M3uEntry>()
        var pending: Pending? = null
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF") -> {
                    // A previous EXTINF that never got a URL is dropped here, superseded.
                    pending = extInf(line)
                }
                line.startsWith("#EXTVLCOPT") -> {
                    val current = pending ?: continue // orphan before any EXTINF: not a header
                    when {
                        line.startsWith(UA_PREFIX) -> current.userAgent = optionValue(line, UA_PREFIX)
                        line.startsWith(REFERRER_PREFIX) -> current.referrer = optionValue(line, REFERRER_PREFIX)
                    }
                }
                line.startsWith("#") -> Unit // #EXTM3U and any other directive: not channel data
                else -> {
                    val current = pending
                    pending = null
                    if (current != null && current.name.isNotBlank()) {
                        entries += M3uEntry(
                            name = current.name,
                            logoUrl = current.logo,
                            groupTitle = current.group ?: FALLBACK_GROUP,
                            url = line,
                            userAgent = current.userAgent?.takeIf(String::isNotBlank),
                            referrer = current.referrer?.takeIf(String::isNotBlank),
                        )
                    }
                }
            }
        }
        return entries
    }

    /**
     * `#EXTINF:<duration> <attrs>,<title>` — the title is everything after the FIRST comma outside
     * double quotes, so a title may carry commas of its own without being cut.
     */
    private fun extInf(line: String): Pending {
        val comma = indexOfTitleComma(line)
        val attributePart = if (comma >= 0) line.substring(0, comma) else line
        val title = if (comma >= 0) line.substring(comma + 1).trim() else ""
        val pending = Pending(name = title)
        for (match in attribute.findAll(attributePart)) {
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            when (match.groupValues[1].lowercase()) {
                "tvg-logo" -> pending.logo = value.takeIf(String::isNotBlank)
                "group-title" -> pending.group = value.takeIf(String::isNotBlank)
            }
        }
        return pending
    }

    /** The first comma that sits outside double quotes, or -1 when the line has none. */
    private fun indexOfTitleComma(line: String): Int {
        var inQuotes = false
        for (index in line.indices) {
            when {
                line[index] == '"' -> inQuotes = !inQuotes
                line[index] == ',' && !inQuotes -> return index
            }
        }
        return -1
    }

    private fun optionValue(line: String, prefix: String): String? =
        line.substringAfter(prefix, missingDelimiterValue = "").trim().takeIf(String::isNotEmpty)

    /** The entry being assembled between its `#EXTINF` and its URL line. */
    private class Pending(
        val name: String,
        var logo: String? = null,
        var group: String? = null,
        var userAgent: String? = null,
        var referrer: String? = null,
    )
}
