package com.nuvio.tv.ext.livetv.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvPagingTest {

    @Test
    fun `keeps paging while there is progress and the addon reports more`() {
        assertTrue(
            LiveTvPaging.shouldRequestAnotherPage(
                LiveTvPageOutcome(pageIndex = 0, itemsOnPage = 100, newItems = 100, addonReportsMore = true)
            )
        )
    }

    @Test
    fun `stops when a page returns nothing`() {
        assertFalse(
            LiveTvPaging.shouldRequestAnotherPage(
                LiveTvPageOutcome(pageIndex = 3, itemsOnPage = 0, newItems = 0, addonReportsMore = true)
            )
        )
    }

    @Test
    fun `stops when a page is pure duplicate`() {
        // An addon that ignores `skip` returns page one forever. Trusting `hasMore` alone would walk
        // it until the page cap on every single load.
        assertFalse(
            LiveTvPaging.shouldRequestAnotherPage(
                LiveTvPageOutcome(pageIndex = 1, itemsOnPage = 100, newItems = 0, addonReportsMore = true)
            )
        )
    }

    @Test
    fun `stops when the addon reports no more`() {
        assertFalse(
            LiveTvPaging.shouldRequestAnotherPage(
                LiveTvPageOutcome(pageIndex = 0, itemsOnPage = 42, newItems = 42, addonReportsMore = false)
            )
        )
    }

    @Test
    fun `stops at the page cap even when the addon keeps claiming more`() {
        assertTrue(
            LiveTvPaging.shouldRequestAnotherPage(
                LiveTvPageOutcome(
                    pageIndex = LiveTvPaging.MAX_PAGES_PER_CATALOG - 2,
                    itemsOnPage = 100,
                    newItems = 100,
                    addonReportsMore = true
                )
            )
        )
        assertFalse(
            LiveTvPaging.shouldRequestAnotherPage(
                LiveTvPageOutcome(
                    pageIndex = LiveTvPaging.MAX_PAGES_PER_CATALOG - 1,
                    itemsOnPage = 100,
                    newItems = 100,
                    addonReportsMore = true
                )
            )
        )
    }
}
