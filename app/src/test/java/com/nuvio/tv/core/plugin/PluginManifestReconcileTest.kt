package com.nuvio.tv.core.plugin

import com.nuvio.tv.domain.model.RepositoryType
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.model.ScraperManifestInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository manifest as source of truth:
 * 1. Scrapers dropped from the manifest are reconciled away (only for that repo).
 * 2. A manifest `enabled` CHANGE overrides the stored `enabled`;
 *    an unchanged manifest `enabled` preserves the user's manual toggle.
 */
class PluginManifestReconcileTest {

    private fun manifestEntry(id: String, enabled: Boolean = true) = ScraperManifestInfo(
        id = id,
        name = id,
        version = "1.0",
        filename = "$id.js",
        enabled = enabled
    )

    private fun scraper(
        repoId: String,
        entryId: String,
        enabled: Boolean,
        manifestEnabled: Boolean
    ) = ScraperInfo(
        id = "$repoId:$entryId",
        name = entryId,
        description = "",
        version = "1.0",
        filename = "$entryId.js",
        supportedTypes = listOf("movie", "tv"),
        enabled = enabled,
        manifestEnabled = manifestEnabled,
        logo = null,
        contentLanguage = emptyList(),
        repositoryId = repoId,
        formats = null,
        type = RepositoryType.NUVIO_JS
    )

    // ---- Reconciliation of deletions ----

    @Test
    fun `manifest dropping an entry marks exactly that repo's scraper for deletion`() {
        val stored = listOf(
            scraper("repo1", "kept", enabled = true, manifestEnabled = true),
            scraper("repo1", "dropped", enabled = true, manifestEnabled = true),
            scraper("repo2", "untouched", enabled = true, manifestEnabled = true)
        )
        val manifest = listOf(manifestEntry("kept"))

        val removed = scrapersRemovedByManifest(
            repoId = "repo1",
            manifestScrapers = manifest,
            storedScrapers = stored
        )

        assertEquals(listOf("repo1:dropped"), removed.map { it.id })
    }

    @Test
    fun `reconciliation never touches other repositories' scrapers`() {
        val stored = listOf(
            scraper("repo2", "a", enabled = true, manifestEnabled = true),
            scraper("repo2", "b", enabled = false, manifestEnabled = true)
        )
        val manifest = listOf(manifestEntry("kept"))

        val removed = scrapersRemovedByManifest(
            repoId = "repo1",
            manifestScrapers = manifest,
            storedScrapers = stored
        )

        assertTrue(removed.isEmpty())
    }

    @Test
    fun `failed manifest fetch deletes nothing`() {
        val stored = listOf(
            scraper("repo1", "a", enabled = true, manifestEnabled = true),
            scraper("repo1", "b", enabled = true, manifestEnabled = true)
        )

        // The caller passes null when the manifest fetch failed.
        val removed = scrapersRemovedByManifest(
            repoId = "repo1",
            manifestScrapers = null,
            storedScrapers = stored
        )

        assertTrue(removed.isEmpty())
    }

    @Test
    fun `manifest-listed scraper whose download failed is not deleted`() {
        // "flaky" is in the manifest but its download failed (absent from the
        // downloaded pass); it must survive, only manifest-absent entries go.
        val stored = listOf(
            scraper("repo1", "flaky", enabled = true, manifestEnabled = true),
            scraper("repo1", "dropped", enabled = true, manifestEnabled = true)
        )
        val manifest = listOf(manifestEntry("flaky"))

        val removed = scrapersRemovedByManifest(
            repoId = "repo1",
            manifestScrapers = manifest,
            storedScrapers = stored
        )

        assertEquals(listOf("repo1:dropped"), removed.map { it.id })
    }

    @Test
    fun `reconciled list keeps other repo scrapers after merge`() {
        val kept = scraper("repo1", "kept", enabled = true, manifestEnabled = true)
        val otherRepo = scraper("repo2", "untouched", enabled = true, manifestEnabled = true)
        val dropped = scraper("repo1", "dropped", enabled = true, manifestEnabled = true)
        val manifest = listOf(manifestEntry("kept"))

        val removed = scrapersRemovedByManifest("repo1", manifest, listOf(kept, otherRepo, dropped))
        val removedIds = removed.map { it.id }.toSet()
        val surviving = listOf(kept, otherRepo, dropped).filter { it.id !in removedIds }
        val merged = mergeScrapersToManifestOrder(
            existingScrapers = surviving,
            manifestOrderedScrapers = listOf(kept)
        )

        assertEquals(listOf("repo1:kept", "repo2:untouched"), merged.map { it.id })
    }

    // ---- Manifest-enabled reconciliation ----

    @Test
    fun `changed manifestEnabled to false overrides stored enabled`() {
        // Owner pushed enabled:false — must turn the installed scraper off.
        val existing = scraper("repo1", "a", enabled = true, manifestEnabled = true)

        val resolved = resolveScraperEnabled(
            existingScraper = existing,
            manifestEnabled = false,
            defaultEnabled = false
        )

        assertEquals(false, resolved)
    }

    @Test
    fun `changed manifestEnabled to true re-enables a disabled scraper`() {
        val existing = scraper("repo1", "a", enabled = false, manifestEnabled = false)

        val resolved = resolveScraperEnabled(
            existingScraper = existing,
            manifestEnabled = true,
            defaultEnabled = true
        )

        assertEquals(true, resolved)
    }

    @Test
    fun `unchanged manifestEnabled preserves user's manual toggle`() {
        // User turned it off manually; manifest still says enabled → stays off.
        val manuallyDisabled = scraper("repo1", "a", enabled = false, manifestEnabled = true)
        assertEquals(
            false,
            resolveScraperEnabled(manuallyDisabled, manifestEnabled = true, defaultEnabled = true)
        )

        // User turned it on manually; manifest still says disabled → stays on.
        val manuallyEnabled = scraper("repo1", "b", enabled = true, manifestEnabled = false)
        assertEquals(
            true,
            resolveScraperEnabled(manuallyEnabled, manifestEnabled = false, defaultEnabled = false)
        )
    }

    @Test
    fun `brand-new scraper gets its manifest default`() {
        val normalDefault = true
        val videasyDefault = false // PluginSafety blocks videasy by default

        assertEquals(
            normalDefault,
            resolveScraperEnabled(null, manifestEnabled = true, defaultEnabled = normalDefault)
        )
        assertEquals(
            videasyDefault,
            resolveScraperEnabled(null, manifestEnabled = true, defaultEnabled = videasyDefault)
        )
        assertEquals(
            false,
            resolveScraperEnabled(null, manifestEnabled = false, defaultEnabled = false)
        )
    }
}
