package com.nuvio.tv.ext.livetv.domain

/** What a single fetched catalog page turned out to be. */
data class LiveTvPageOutcome(
    /** Zero-based index of the page that was just read. */
    val pageIndex: Int,
    /** Items the addon returned on this page, duplicates included. */
    val itemsOnPage: Int,
    /** Items that were not already known. Zero means the page made no progress. */
    val newItems: Int,
    /** Whether the addon signalled that more pages exist. */
    val addonReportsMore: Boolean
)

/**
 * Decides whether to keep paging a catalog.
 *
 * Two of these conditions exist because "the addon says there is more" is not trustworthy on its own:
 *
 * - A page that returns no items at all ends the walk, whatever the addon claims.
 * - A page made entirely of duplicates ends it too. Without that rule a single addon that ignores
 *   `skip` returns page 1 forever. The reference fork instead tracked `consecutiveDuplicatePages` as
 *   part of its row state, which leaked a paging concern into the UI model.
 * - The page cap bounds the worst case: a catalog of unknown size must not turn into an unbounded
 *   burst of requests, since the addon pays for each one upstream.
 */
object LiveTvPaging {

    /** Worst-case page count per catalog. */
    const val MAX_PAGES_PER_CATALOG: Int = 15

    fun shouldRequestAnotherPage(outcome: LiveTvPageOutcome): Boolean = when {
        outcome.itemsOnPage <= 0 -> false
        outcome.newItems <= 0 -> false
        !outcome.addonReportsMore -> false
        outcome.pageIndex + 1 >= MAX_PAGES_PER_CATALOG -> false
        else -> true
    }
}
