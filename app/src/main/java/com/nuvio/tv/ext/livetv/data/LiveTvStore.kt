package com.nuvio.tv.ext.livetv.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.ext.livetv.domain.EpgSourceDiscovery
import com.nuvio.tv.ext.livetv.domain.LiveTvSports
import com.nuvio.tv.ext.livetv.domain.model.EpgSource
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategoryId
import com.nuvio.tv.ext.livetv.domain.model.preferenceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One native source's catalog, as last observed by the Live TV screen and remembered for the
 * settings pane.
 *
 * The three identity fields are exactly what rebuilding the pane row and the hiding key needs: the
 * source's display name groups the rows, and `addonBaseUrl + catalogId` rebuild the SAME typed
 * [LiveTvCategoryId] the screen's slider keys its hiding on — never the display text, which either
 * source may rename. Nothing else of a native load (channels, api type, paging support) survives
 * here: the pane only flips toggles.
 *
 * Persisted as one encoded string per entry inside a string-set preference, the same convention
 * every other set in this store uses — see [encode] for the one asymmetry that buys it.
 */
data class RememberedNativeCatalog(
    val provider: String,
    val addonBaseUrl: String,
    val catalogId: String,
    val label: String
) {
    /** The typed identity; [LiveTvCategoryId.preferenceKey] on it is the hiding key. */
    val categoryId: LiveTvCategoryId get() = LiveTvCategoryId.Addon(addonBaseUrl, catalogId)

    /**
     * One string carrying all four fields. The separator is the ASCII unit separator, which no
     * addon name, sentinel URL, catalog id or source label can realistically contain. [decode]
     * demands EXACTLY four fields: a field that somehow carries the separator is unrepresentable
     * and is dropped at read time rather than silently re-split into a wrong row.
     */
    fun encode(): String = listOf(provider, addonBaseUrl, catalogId, label).joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = "\u001F"

        /** Null for anything not shaped exactly like an [encode] output: a corrupt entry never becomes a row. */
        fun decode(raw: String): RememberedNativeCatalog? {
            val parts = raw.split(SEPARATOR)
            if (parts.size != 4) return null
            return RememberedNativeCatalog(parts[0], parts[1], parts[2], parts[3])
        }
    }
}

/**
 * The feature's own settings, per profile like everything else in this app.
 *
 * ### The two defaults are chosen, not inherited
 *
 * **The adult filter is on.** The plan says so, and the safe direction to be wrong in.
 *
 * **The third-party country feeds are off.** Those ten fallbacks are real value -- they cover channels
 * the addon's own guide misses -- but turning them on by default would mean ten downloads of tens of
 * megabytes and, more to the point, contacting a host unconnected to Nuvio before the user has seen
 * anything. The settings screen explains what they are and offers them one by one. The addon's own guide
 * needs no such consent, which is why the guide already works out of the box.
 *
 * Disabled ids are stored rather than enabled ones, so the empty set means "everything on that is
 * allowed to be on" instead of a three-way question between null, empty and populated.
 */
@Singleton
class LiveTvStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    private val hideAdultKey = booleanPreferencesKey("livetv_hide_adult_channels")
    private val disabledEpgSourcesKey = stringSetPreferencesKey("livetv_disabled_epg_sources")
    private val hiddenCategoriesKey = stringSetPreferencesKey("livetv_hidden_categories")
    private val disabledSportsKey = stringSetPreferencesKey("livetv_disabled_sports")
    private val knownSportsKey = stringSetPreferencesKey("livetv_known_sports")
    private val rememberedNativeCatalogsKey = stringSetPreferencesKey("livetv_remembered_native_catalogs")
    private val favoritesKey = stringSetPreferencesKey("livetv_favorite_channels")

    val hideAdultChannels: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { preferences ->
            preferences[hideAdultKey] ?: true
        }
    }

    val disabledEpgSourceIds: Flow<Set<String>> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { preferences ->
            preferences[disabledEpgSourcesKey] ?: defaultDisabledEpgSourceIds()
        }
    }

    /**
     * The slider categories the user removed, by [com.nuvio.tv.ext.livetv.domain.model.preferenceKey].
     *
     * Stored as the hidden set, like the guide sources: empty means "everything visible", which is the
     * default. The two built-in categories are not hideable, so nothing here can remove `All` or
     * `Favorites` even if a stray key found its way in.
     */
    val hiddenCategoryIds: Flow<Set<String>> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { preferences ->
            preferences[hiddenCategoriesKey] ?: emptySet()
        }
    }

    /**
     * The sports the user turned off, by [LiveTvSports.sportKey]. Stored as the disabled set like
     * every other toggle in this store: empty means "everything on", the default.
     */
    val disabledSports: Flow<Set<String>> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { preferences ->
            preferences[disabledSportsKey] ?: emptySet()
        }
    }

    /**
     * The channels the user starred, by stable key. Stored as the marked set like every other
     * toggle in this store: empty means "none marked", the default.
     */
    val favorites: Flow<Set<String>> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { preferences ->
            preferences[favoritesKey] ?: emptySet()
        }
    }

    /**
     * The sports the addon's matches actually carried, as last observed by the Live TV screen.
     *
     * WHY this lives in the store and nowhere else: the settings screen never loads channels -- its
     * ViewModel's documented design -- so it cannot derive the sport list from matches. Enumerating
     * the settings rows from the settings state's own `matches` was the empty-list bug the review
     * caught: that state never carries matches, so no row was ever emitted. The screen, which HAS
     * the matches, publishes the list it observed through [rememberSports], and settings reads it
     * back -- the same store-as-bridge pattern every preference above already uses.
     *
     * Empty means "nothing observed yet": before the user first opens Partidos nothing has been
     * seen, and the settings group shows its header with no rows.
     */
    val knownSports: Flow<Set<String>> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { preferences ->
            preferences[knownSportsKey] ?: emptySet()
        }
    }

    /**
     * The native sources' catalogs as last observed by the Live TV screen, in observation order.
     *
     * WHY this lives in the store and nowhere else: the settings screen never loads channels or
     * catalogs -- its ViewModel's documented design, the same one that routes the sport rows through
     * [knownSports]. Magis walks its portal (~38 categories) and PremiumTV downloads and parses an
     * M3U list; a Settings pane doing either to flip a toggle is exactly the price the design
     * refuses to pay. The screen, which ALREADY loads the native catalogs for its own slider,
     * publishes what it saw through [rememberNativeCatalogs], and settings reads it back -- the
     * same store-as-bridge pattern every preference above already uses.
     *
     * Empty means "nothing observed yet": before the user first opens Live TV no native catalog has
     * been seen, and the pane renders no native group at all rather than an empty header.
     */
    val rememberedNativeCatalogs: Flow<List<RememberedNativeCatalog>> =
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        profileManager.activeProfileId.flatMapLatest { profileId ->
            factory.get(profileId, FEATURE).data.map { preferences ->
                (preferences[rememberedNativeCatalogsKey] ?: emptySet())
                    .mapNotNull(RememberedNativeCatalog::decode)
            }
        }

    suspend fun setHideAdultChannels(hide: Boolean) {
        store().edit { it[hideAdultKey] = hide }
    }

    suspend fun setEpgSourceEnabled(sourceId: String, enabled: Boolean) {
        store().edit { preferences ->
            val current = preferences[disabledEpgSourcesKey] ?: defaultDisabledEpgSourceIds()
            preferences[disabledEpgSourcesKey] = if (enabled) current - sourceId else current + sourceId
        }
    }

    /** Turns a slider category on ([visible] true) or off by adding or removing it from the hidden set. */
    suspend fun setCategoryVisible(categoryKey: String, visible: Boolean) {
        store().edit { preferences ->
            val current = preferences[hiddenCategoriesKey] ?: emptySet()
            preferences[hiddenCategoriesKey] = if (visible) current - categoryKey else current + categoryKey
        }
    }

    /**
     * Replaces the remembered sport observation with [sports]. The write is a wholesale replace, not
     * a merge: the screen observed the addon's CURRENT catalog, and sports it stopped publishing
     * should stop being offered as toggles.
     */
    suspend fun rememberSports(sports: Set<String>) {
        store().edit { it[knownSportsKey] = sports }
    }

    /**
     * Replaces the remembered native-catalog observation with [catalogs]. The write is a wholesale
     * replace, not a merge: the screen observed the native sources' CURRENT catalogs, and a category
     * a source stopped publishing must stop being offered as a toggle.
     */
    suspend fun rememberNativeCatalogs(catalogs: List<RememberedNativeCatalog>) {
        store().edit { it[rememberedNativeCatalogsKey] = catalogs.map(RememberedNativeCatalog::encode).toSet() }
    }

    /** Turns a sport's matches on ([enabled] true) or off in the Partidos section. */
    suspend fun setSportEnabled(sportKey: String, enabled: Boolean) {
        store().edit { preferences ->
            val current = preferences[disabledSportsKey] ?: emptySet()
            preferences[disabledSportsKey] = if (enabled) current - sportKey else current + sportKey
        }
    }

    /**
     * Stars ([favorite] true) or unstars one channel by stable key.
     *
     * WHY read-modify-write inside the edit instead of writing a set captured outside: a DataStore
     * `edit` is serialized per file, so reading the CURRENT set inside the lambda means two toggles
     * racing (one per row, or drawer plus panel) each apply their own single-key delta on top of
     * whatever landed first, and neither one's star is lost. A wholesale replace with a stale set
     * would silently drop the other toggle.
     */
    suspend fun setFavorite(stableKey: String, favorite: Boolean) {
        store().edit { preferences ->
            val current = preferences[favoritesKey] ?: emptySet()
            preferences[favoritesKey] = if (favorite) current + stableKey else current - stableKey
        }
    }

    private companion object {
        const val FEATURE = "livetv_settings"

        /** Read at the point of use, so the default follows the list rather than freezing a copy. */
        fun defaultDisabledEpgSourceIds(): Set<String> =
            EpgSourceDiscovery.BUILT_IN.map(EpgSource::id).toSet()
    }
}
