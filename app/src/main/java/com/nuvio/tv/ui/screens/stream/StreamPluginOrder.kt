package com.nuvio.tv.ui.screens.stream

/**
 * The plugin order the stream screen sorts groups by: native VOD sources FIRST (they are not in
 * the scraper registry, so without this they would fall into `orderAddonStreams`'s
 * unknownPluginEntries and land last by arrival time), then the scraper registry order the
 * plugin groups already followed. Names with no group are simply never matched.
 */
internal fun nativeFirstPluginOrder(
    nativeSourceNames: List<String>,
    scraperNames: List<String>,
): List<String> = nativeSourceNames + scraperNames
