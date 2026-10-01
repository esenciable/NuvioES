package com.nuvio.tv.ext.livetv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.ext.livetv.data.LiveTvStore
import com.nuvio.tv.ext.livetv.domain.EpgSourceDiscovery
import com.nuvio.tv.ext.livetv.domain.LiveTvSettingsState
import com.nuvio.tv.ext.livetv.domain.LiveTvSports
import com.nuvio.tv.ext.livetv.domain.LiveTvCatalog
import com.nuvio.tv.ext.livetv.domain.TvCatalogSelector
import com.nuvio.tv.ext.livetv.domain.model.EpgSource
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategoryId
import com.nuvio.tv.ext.livetv.domain.model.LiveTvUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The settings pane's state, without the screen behind it.
 *
 * [LiveTvViewModel] loads the full channel catalog and syncs the guide in its init, which is exactly
 * what the screen needs and exactly what a Settings pane must not do: the user opened Settings to
 * flip toggles, not to fetch 1170 channels and parse an XMLTV document. This ViewModel builds the
 * same five fields the pane reads from the cheap sources only -- catalogs selected and guide sources
 * discovered (both pure), the three preferences observed live from [LiveTvStore] -- and delegates
 * every toggle to the same store the screen observes, so the two entry points can never disagree.
 */
@HiltViewModel
class LiveTvSettingsViewModel @Inject constructor(
    addonRepository: AddonRepository,
    private val liveTvStore: LiveTvStore
) : ViewModel() {

    private val _state = MutableStateFlow(LiveTvUiState())
    val state: StateFlow<LiveTvUiState> = _state.asStateFlow()

    private var catalogs: List<LiveTvCatalog> = emptyList()
    private var epgSources: List<EpgSource> = emptyList()
    private var hideAdultChannels: Boolean = true
    private var disabledEpgSourceIds: Set<String> = emptySet()
    private var hiddenCategoryIds: Set<String> = emptySet()
    private var disabledSports: Set<String> = emptySet()
    /** Sports the screen observed the addon publishing; the pane's toggle rows come from this. */
    private var knownSports: List<String> = emptyList()

    init {
        viewModelScope.launch {
            val addons = runCatching { addonRepository.getInstalledAddons().first() }
                .getOrDefault(emptyList())
            catalogs = TvCatalogSelector.select(addons)

            // Derived exactly as the screen derives them: only the addons that publish a TV catalog,
            // and the full list of sources (enabled or not), so a disabled one can be turned back on.
            epgSources = EpgSourceDiscovery.discover(
                addons = catalogs.map { it.addonBaseUrl to it.addonName }.distinct(),
                userSources = emptyList(),
                disabledIds = emptySet()
            )
            publish()
        }
        // The store flows replay their current value on collect, so the pane is consistent within a
        // frame of the catalogs landing, even if the addons answer first.
        viewModelScope.launch {
            liveTvStore.hideAdultChannels.collect { hide ->
                hideAdultChannels = hide
                publish()
            }
        }
        viewModelScope.launch {
            liveTvStore.disabledEpgSourceIds.collect { disabled ->
                disabledEpgSourceIds = disabled
                publish()
            }
        }
        viewModelScope.launch {
            liveTvStore.hiddenCategoryIds.collect { hidden ->
                hiddenCategoryIds = hidden
                publish()
            }
        }
        viewModelScope.launch {
            liveTvStore.disabledSports.collect { disabled ->
                disabledSports = disabled
                publish()
            }
        }
        viewModelScope.launch {
            liveTvStore.knownSports.collect { observed ->
                // The store is a set; the pane wants a stable order, so re-apply the same sort the
                // screen's own sportKeysOf produces -- alphabetical, other bucket last. Reading the
                // store here is the whole point: no channel load ever happens in this ViewModel.
                knownSports = LiveTvSports.sortForDisplay(observed)
                publish()
            }
        }
    }

    fun setAdultFilter(hide: Boolean) {
        viewModelScope.launch { liveTvStore.setHideAdultChannels(hide) }
    }

    fun setEpgSourceEnabled(sourceId: String, enabled: Boolean) {
        viewModelScope.launch { liveTvStore.setEpgSourceEnabled(sourceId, enabled) }
    }

    /**
     * Shows or hides one category in the slider.
     *
     * The id-to-key mapping carries the guard: `All` and `Favorites` cannot be hidden, so asking to
     * hide them never reaches the store -- the same invariant the pure filter enforces, enforced at
     * the point where a display id would become a stored key.
     */
    fun setCategoryVisible(categoryId: LiveTvCategoryId, visible: Boolean) {
        val key = LiveTvSettingsState.hideableKey(categoryId) ?: return
        viewModelScope.launch { liveTvStore.setCategoryVisible(key, visible) }
    }

    /** Turns a sport's matches on or off; the store flow re-publishes to both entry points. */
    fun setSportEnabled(sportKey: String, enabled: Boolean) {
        viewModelScope.launch { liveTvStore.setSportEnabled(sportKey, enabled) }
    }

    private fun publish() {
        _state.value = LiveTvSettingsState.build(
            catalogs = catalogs,
            epgSources = epgSources,
            hideAdultChannels = hideAdultChannels,
            disabledEpgSourceIds = disabledEpgSourceIds,
            hiddenCategoryIds = hiddenCategoryIds,
            disabledSports = disabledSports,
            knownSports = knownSports
        )
    }
}
