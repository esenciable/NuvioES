package com.nuvio.tv.ext.livetv.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.ext.livetv.domain.EpgSourceDiscovery
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

    suspend fun setHideAdultChannels(hide: Boolean) {
        store().edit { it[hideAdultKey] = hide }
    }

    suspend fun setEpgSourceEnabled(sourceId: String, enabled: Boolean) {
        store().edit { preferences ->
            val current = preferences[disabledEpgSourcesKey] ?: defaultDisabledEpgSourceIds()
            preferences[disabledEpgSourcesKey] = if (enabled) current - sourceId else current + sourceId
        }
    }

    private companion object {
        const val FEATURE = "livetv_settings"

        /** Read at the point of use, so the default follows the list rather than freezing a copy. */
        fun defaultDisabledEpgSourceIds(): Set<String> =
            EpgSourceDiscovery.BUILT_IN.map(EpgSource::id).toSet()
    }
}
