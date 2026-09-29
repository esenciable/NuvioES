package com.nuvio.tv.ext.livetv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.ext.livetv.data.CatalogChannelLoader
import com.nuvio.tv.ext.livetv.data.epg.EpgSnapshot
import com.nuvio.tv.ext.livetv.domain.LiveTvCatalog
import com.nuvio.tv.ext.livetv.domain.LiveTvRows
import com.nuvio.tv.ext.livetv.domain.TvCatalogSelector
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategory
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategoryId
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannelRow
import com.nuvio.tv.ext.livetv.domain.model.LiveTvStatus
import com.nuvio.tv.ext.livetv.domain.model.LiveTvUiState
import com.nuvio.tv.ext.livetv.domain.model.inCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The single source of truth for the Live TV screen.
 *
 * The guide is not wired in yet: rows are built against an empty [EpgSnapshot], so `now` and `next`
 * are null and the UI says so rather than pretending. Everything else is real -- the channels come from
 * the addons the user has installed, through upstream's own catalog repository, so the only thing
 * missing from this screen is the programming.
 *
 * The loader is built here rather than injected. It needs nothing but [CatalogRepository], which Hilt
 * already provides, so a binding for it would be a module that exists to describe one constructor call.
 */
@HiltViewModel
class LiveTvViewModel @Inject constructor(
    addonRepository: AddonRepository,
    catalogRepository: CatalogRepository
) : ViewModel() {

    private val addonRepository = addonRepository
    private val channelLoader = CatalogChannelLoader(catalogRepository)

    private val _state = MutableStateFlow(LiveTvUiState())
    val state: StateFlow<LiveTvUiState> = _state.asStateFlow()

    private var catalogs: List<LiveTvCatalog> = emptyList()
    private var channels: List<LiveTvChannel> = emptyList()

    /** Favourites arrive with the settings screen; empty means "none marked", not "unknown". */
    private var favorites: Set<String> = emptySet()
    private var selectedCategory: LiveTvCategoryId = LiveTvCategoryId.All

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(status = LiveTvStatus.LOADING, errorMessage = null) }

            val addons = runCatching { addonRepository.getInstalledAddons().first() }.getOrElse { failure ->
                publish(
                    status = LiveTvStatus.ERROR,
                    message = failure.message ?: failure::class.java.simpleName
                )
                return@launch
            }

            catalogs = TvCatalogSelector.select(addons)
            if (catalogs.isEmpty()) {
                // The addons answered and none publishes live television. That is EMPTY, not an error:
                // the reference fork showed "no channels found" for a network failure too, so the user
                // could not tell an outage from an untouched setup.
                channels = emptyList()
                publish(status = LiveTvStatus.EMPTY, message = null)
                return@launch
            }

            val loaded = runCatching { channelLoader.load(catalogs) }.getOrElse { failure ->
                channels = emptyList()
                publish(
                    status = LiveTvStatus.ERROR,
                    message = failure.message ?: failure::class.java.simpleName
                )
                return@launch
            }

            channels = loaded.channels
            publish(
                status = if (channels.isEmpty()) LiveTvStatus.EMPTY else LiveTvStatus.READY,
                message = null,
                failedCatalogs = loaded.failedCatalogs
            )
        }
    }

    fun selectCategory(categoryId: LiveTvCategoryId) {
        selectedCategory = categoryId
        publish(status = _state.value.status, message = _state.value.errorMessage)
    }

    fun selectChannel(stableKey: String) {
        _state.update { it.copy(selectedChannelKey = stableKey) }
    }

    private fun publish(
        status: LiveTvStatus,
        message: String?,
        failedCatalogs: Int = 0
    ) {
        val aliases = emptyMap<String, String>()
        val visibleChannels = channels.inCategory(
            category = LiveTvCategory(selectedCategory),
            isFavorite = { it.stableKey in favorites }
        )
        val rows: List<LiveTvChannelRow> = LiveTvRows.build(
            channels = visibleChannels,
            guide = EpgSnapshot.EMPTY,
            aliases = aliases,
            favorites = favorites,
            nowEpochMs = System.currentTimeMillis()
        )
        val categories = LiveTvRows.categoriesFor(catalogs)

        _state.update { current ->
            val selectionStillVisible = rows.any { it.channel.stableKey == current.selectedChannelKey }
            current.copy(
                status = status,
                channels = rows,
                categories = categories,
                selectedCategory = selectedCategory,
                selectedChannelKey = current.selectedChannelKey.takeIf { selectionStillVisible },
                totalChannelCount = channels.size,
                failedCatalogs = failedCatalogs,
                errorMessage = message
            )
        }
    }
}
