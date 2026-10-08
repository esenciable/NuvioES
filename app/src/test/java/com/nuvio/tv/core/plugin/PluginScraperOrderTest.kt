package com.nuvio.tv.core.plugin

import com.nuvio.tv.domain.model.RepositoryType
import com.nuvio.tv.domain.model.ScraperInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginScraperOrderTest {

    private fun scraper(id: String, name: String = id) = ScraperInfo(
        id = id,
        name = name,
        description = "",
        version = "1.0",
        filename = "$id.js",
        supportedTypes = listOf("movie", "tv"),
        enabled = true,
        manifestEnabled = true,
        logo = null,
        contentLanguage = emptyList(),
        repositoryId = "repo",
        formats = null,
        type = RepositoryType.NUVIO_JS
    )

    @Test
    fun `merge puts manifest scrapers first in manifest order and preserves extras after`() {
        // Old stored order: a, z, b (upsert kept stale positions).
        val existing = listOf(scraper("a"), scraper("z"), scraper("b"))
        // New manifest order: b, a (e.g. magis moved to the front).
        val downloaded = listOf(scraper("b"), scraper("a"))

        val merged = mergeScrapersToManifestOrder(
            existingScrapers = existing,
            manifestOrderedScrapers = downloaded
        )

        assertEquals(listOf("b", "a", "z"), merged.map { it.id })
    }

    @Test
    fun `merge uses updated scraper for manifest ids and keeps stored version for extras`() {
        val oldMagis = scraper("magis", name = "Old Magis")
        val other = scraper("other")
        val updatedMagis = scraper("magis", name = "Magis")

        val merged = mergeScrapersToManifestOrder(
            existingScrapers = listOf(oldMagis, other),
            manifestOrderedScrapers = listOf(updatedMagis)
        )

        assertEquals(listOf(updatedMagis, other), merged)
    }

    @Test
    fun `merge does not drop scrapers that failed to download or are not in the manifest`() {
        val stale = scraper("stale")
        val otherRepo = scraper("other-repo-scraper")

        val merged = mergeScrapersToManifestOrder(
            existingScrapers = listOf(stale, otherRepo),
            manifestOrderedScrapers = emptyList()
        )

        assertEquals(listOf(stale, otherRepo), merged)
    }

    @Test
    fun `merge with empty existing list returns manifest order`() {
        val downloaded = listOf(scraper("c"), scraper("a"), scraper("b"))

        val merged = mergeScrapersToManifestOrder(
            existingScrapers = emptyList(),
            manifestOrderedScrapers = downloaded
        )

        assertEquals(downloaded, merged)
    }
}
