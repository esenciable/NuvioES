package com.nuvio.tv.ext.livetv.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.ext.livetv.domain.EpgSourceDiscovery
import com.nuvio.tv.ext.livetv.domain.LiveTvSports
import com.nuvio.tv.ext.livetv.domain.model.EpgSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

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

    /** Turns a sport's matches on ([enabled] true) or off in the Partidos section. */
    suspend fun setSportEnabled(sportKey: String, enabled: Boolean) {
        store().edit { preferences ->
            val current = preferences[disabledSportsKey] ?: emptySet()
            preferences[disabledSportsKey] = if (enabled) current - sportKey else current + sportKey
        }
    }

    private companion object {
        const val FEATURE = "livetv_settings"

        /** Read at the point of use, so the default follows the list rather than freezing a copy. */
        fun defaultDisabledEpgSourceIds(): Set<String> =
            EpgSourceDiscovery.BUILT_IN.map(EpgSource::id).toSet()
    }
}
