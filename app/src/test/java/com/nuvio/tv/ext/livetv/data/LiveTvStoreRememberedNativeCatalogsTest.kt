package com.nuvio.tv.ext.livetv.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.nuvio.tv.ext.livetv.domain.model.preferenceKey
import java.nio.file.Files

/**
 * The remembered-native-catalogs slice of [LiveTvStore]: the bridge that lets the settings pane list
 * the native sources' categories without ever loading a catalog itself. The store write is a
 * wholesale replace, exactly like [LiveTvStore.rememberSports] -- the screen observed the sources'
 * CURRENT catalogs, so whatever stopped being published must disappear.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvStoreRememberedNativeCatalogsTest {

    @Test
    fun `round trips the remembered native catalogs through the store`() = runTest {
        val store = newStore(testScheduler)

        store.rememberNativeCatalogs(
            listOf(
                remembered("Magis", "magis://native-live", "3", "Deportes"),
                remembered("PremiumTV", "premiumtv://native-live", "Deportes", "Deportes")
            )
        )

        assertEquals(
            listOf(
                remembered("Magis", "magis://native-live", "3", "Deportes"),
                remembered("PremiumTV", "premiumtv://native-live", "Deportes", "Deportes")
            ),
            store.rememberedNativeCatalogs.first()
        )
    }

    @Test
    fun `replace is wholesale - a catalog the sources stopped publishing disappears`() = runTest {
        val store = newStore(testScheduler)
        store.rememberNativeCatalogs(
            listOf(
                remembered("Magis", "magis://native-live", "3", "Deportes"),
                remembered("Magis", "magis://native-live", "7", "Cine"),
                remembered("PremiumTV", "premiumtv://native-live", "Deportes", "Deportes")
            )
        )

        store.rememberNativeCatalogs(
            listOf(remembered("Magis", "magis://native-live", "3", "Deportes"))
        )

        assertEquals(
            listOf(remembered("Magis", "magis://native-live", "3", "Deportes")),
            store.rememberedNativeCatalogs.first()
        )
    }

    @Test
    fun `replacing with an empty observation empties the remembered list`() = runTest {
        val store = newStore(testScheduler)
        store.rememberNativeCatalogs(
            listOf(remembered("PremiumTV", "premiumtv://native-live", "Deportes", "Deportes"))
        )

        store.rememberNativeCatalogs(emptyList())

        assertEquals(emptyList<RememberedNativeCatalog>(), store.rememberedNativeCatalogs.first())
    }

    @Test
    fun `a fresh store reads as nothing observed`() = runTest {
        assertEquals(emptyList<RememberedNativeCatalog>(), newStore(testScheduler).rememberedNativeCatalogs.first())
    }

    @Test
    fun `encode and decode round trip every field`() {
        val entry = remembered("Magis", "magis://native-live", "3", "Deportes")

        assertEquals(entry, RememberedNativeCatalog.decode(entry.encode()))
    }

    @Test
    fun `malformed entries decode to null instead of a half-built catalog`() {
        assertNull(RememberedNativeCatalog.decode("only-two\u001Fparts"))
        assertNull(RememberedNativeCatalog.decode(""))
        // A field carrying the separator is unrepresentable: it is dropped, never re-split into a
        // row whose catalogId or label reads something the source never published.
        assertNull(
            RememberedNativeCatalog.decode(
                RememberedNativeCatalog("PremiumTV", "premiumtv://native-live", "Noticias 24\u001Fh", "Noticias 24\u001Fh").encode()
            )
        )
    }

    @Test
    fun `the category id rebuilds the same hiding key the screen filters by`() {
        val entry = remembered("Magis", "magis://native-live", "3", "Deportes")

        assertEquals("addon:magis://native-live|3", entry.categoryId.preferenceKey)
    }

    private fun newStore(scheduler: TestCoroutineScheduler): LiveTvStore {
        val tmp = Files.createTempDirectory("livetv-store-test")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher(scheduler) + Job()),
            produceFile = { tmp.resolve("livetv_test.preferences_pb").toFile() }
        )
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(any(), any()) } returns dataStore
        }
        val profileManager = mockk<ProfileManager> {
            every { activeProfileId } returns MutableStateFlow(1)
        }
        return LiveTvStore(factory, profileManager)
    }

    private fun remembered(provider: String, baseUrl: String, catalogId: String, label: String) =
        RememberedNativeCatalog(
            provider = provider,
            addonBaseUrl = baseUrl,
            catalogId = catalogId,
            label = label
        )
}
