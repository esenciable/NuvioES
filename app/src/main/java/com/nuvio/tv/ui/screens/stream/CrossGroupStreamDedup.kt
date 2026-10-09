package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream

/**
 * The surviving groups plus the names of the groups this dedup EMPTIED, so the UI can hide their
 * source chips and provider entries too — a source with zero surviving streams must not render.
 */
internal data class CrossGroupDedupResult(
    val groups: List<AddonStreams>,
    val droppedGroupNames: Set<String>,
)

/**
 * Drops a stream whose URL was already provided by an EARLIER group, keeping the first
 * occurrence. Run this AFTER `orderAddonStreams`: groups are ordered by `pluginOrder` with native
 * sources prepended (`nativeFirstPluginOrder`), so when a native source and a scraper resolve the
 * exact same CDN URL, the native row survives and the scraper's duplicate disappears — while the
 * scraper still acts as a fallback whenever the native source yields nothing.
 *
 * Comparison is by `Stream.url` only: trimmed, otherwise exact. Signed URLs differ in their query
 * string and must not be merged, so nothing else is normalised (no lowercasing, no query
 * stripping). Streams without a URL (infoHash / clientResolve / debrid entries) cannot be
 * compared and are never touched. Within a single group nothing is deduplicated — the
 * repository's own `dedupKey` behaviour stays authoritative there.
 */
internal fun deduplicateStreamsAcrossGroups(groups: List<AddonStreams>): CrossGroupDedupResult {
    val urlsSeenInEarlierGroups = HashSet<String>()
    val droppedGroupNames = LinkedHashSet<String>()
    val survivingGroups = ArrayList<AddonStreams>(groups.size)
    for (group in groups) {
        if (group.streams.isEmpty()) {
            // Pre-existing empty groups are not this dedup's doing; leave them as-is.
            survivingGroups += group
            continue
        }
        // Compare against EARLIER groups only: a group's own members are never measured against
        // each other, so within-group duplicates keep today's behaviour.
        val kept = group.streams.filter { stream ->
            val url = stream.url?.trim()
            url.isNullOrEmpty() || url !in urlsSeenInEarlierGroups
        }
        when {
            kept.isEmpty() -> {
                // Every stream was a duplicate of an earlier group: the group is gone.
                droppedGroupNames += group.addonName
            }
            kept.size == group.streams.size -> survivingGroups += group
            else -> survivingGroups += group.copy(streams = kept)
        }
        group.streams.forEach { stream ->
            stream.url?.trim()?.takeIf { it.isNotEmpty() }?.let(urlsSeenInEarlierGroups::add)
        }
    }
    return CrossGroupDedupResult(survivingGroups, droppedGroupNames)
}
