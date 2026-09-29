package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.ext.livetv.domain.model.EpgSource
import com.nuvio.tv.ext.livetv.domain.model.EpgSourceOrigin

/**
 * Works out which XMLTV documents to read.
 *
 * ### The addon sources need no configuration at all
 *
 * The addon publishes its guide next to its manifest, and states the convention itself:
 *
 * ```js
 * const base = manifestUrl(request, token).replace(/\/manifest\.json$/, '');
 * // -> `${base}/epg.xml`
 * ```
 *
 * Our side mirrors it exactly. Upstream's `Addon.baseUrl` is already canonical -- it has had
 * `/manifest.json` stripped from the path while any query string was preserved -- so the guide sits at
 * `path + "/epg.xml" + query`. Getting that split wrong is the whole trap here: appending to the raw
 * string would produce `https://host/token?x=1/epg.xml`, where the query swallows the path.
 *
 * The payoff is that a user who already has the addon installed gets a working guide without pasting
 * a URL, which is the difference between a feature and a configuration exercise.
 */
object EpgSourceDiscovery {

    private const val MANIFEST_SUFFIX = "/manifest.json"

    /**
     * The guide URL for an addon, or null when the base URL cannot carry one.
     *
     * Mirrors upstream's `canonicalizeUrl`: the manifest suffix is stripped from the **path** with the
     * query kept aside, then put back.
     */
    fun epgUrlForAddon(addonBaseUrl: String): String? {
        val trimmed = addonBaseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null

        val queryStart = trimmed.indexOf('?')
        val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
        val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""

        val cleanPath = if (path.endsWith(MANIFEST_SUFFIX, ignoreCase = true)) {
            path.dropLast(MANIFEST_SUFFIX.length).trimEnd('/')
        } else {
            path.trimEnd('/')
        }
        if (cleanPath.isEmpty()) return null

        return "$cleanPath/epg.xml$query"
    }

    fun fromAddon(addonBaseUrl: String, addonName: String): EpgSource? {
        val url = epgUrlForAddon(addonBaseUrl) ?: return null
        return EpgSource(
            id = addonSourceId(addonBaseUrl),
            name = addonName,
            url = url,
            origin = EpgSourceOrigin.FromAddon(addonBaseUrl)
        )
    }

    /** Stable across reloads and independent of the addon's display name. */
    fun addonSourceId(addonBaseUrl: String): String = "addon:${addonBaseUrl.trim().trimEnd('/')}"

    /**
     * Fallback country feeds from `epgshare01.online`, which publishes one XMLTV document per country
     * tag. Verified against the provider's own index.
     *
     * Hispanic coverage comes first because that is the audience this fork is for: the reference fork
     * shipped only Brazil, Portugal and the United States, which left the guide empty for Spanish
     * speaking users. These are place names, so they are not translated.
     */
    val BUILT_IN: List<EpgSource> = listOf(
        builtIn("AR1", "Argentina"),
        builtIn("MX1", "México"),
        builtIn("CO1", "Colombia"),
        builtIn("CL1", "Chile"),
        builtIn("PE1", "Perú"),
        builtIn("UY1", "Uruguay"),
        builtIn("ES1", "España"),
        builtIn("US1", "Estados Unidos"),
        builtIn("BR1", "Brasil"),
        builtIn("PT1", "Portugal")
    )

    /**
     * Every source worth trying, in priority order: the addon's own guide first (it is built from the
     * very channels being listed, so its ids match), then the user's own URLs, then the fallbacks.
     *
     * [disabledIds] applies uniformly, so the user can switch off a built-in feed just as easily as an
     * addon one -- including switching them all off, which is what RF-38 requires in exchange for
     * telling the user that guide data comes from third parties.
     */
    fun discover(
        addons: List<Pair<String, String>>,
        userSources: List<EpgSource> = emptyList(),
        disabledIds: Set<String> = emptySet()
    ): List<EpgSource> = (
        addons.mapNotNull { (baseUrl, name) -> fromAddon(baseUrl, name) } +
            userSources +
            BUILT_IN
        )
        .distinctBy(EpgSource::id)
        .filterNot { it.id in disabledIds }

    private fun builtIn(tag: String, label: String) = EpgSource(
        id = "builtin:$tag",
        name = label,
        url = "https://epgshare01.online/epgshare01/epg_ripper_$tag.xml.gz",
        origin = EpgSourceOrigin.BuiltIn
    )
}
