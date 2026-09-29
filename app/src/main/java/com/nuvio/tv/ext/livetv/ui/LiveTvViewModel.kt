package com.nuvio.tv.ext.livetv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.ext.livetv.data.AddonStreamResolver
import com.nuvio.tv.ext.livetv.data.CatalogChannelLoader
import com.nuvio.tv.ext.livetv.data.epg.EpgRepository
import com.nuvio.tv.ext.livetv.data.epg.EpgSnapshot
import com.nuvio.tv.ext.livetv.data.epg.EpgSyncResult
import com.nuvio.tv.ext.livetv.domain.EpgSourceDiscovery
import com.nuvio.tv.ext.livetv.domain.LiveTvCatalog
import com.nuvio.tv.ext.livetv.domain.LiveTvRows
import com.nuvio.tv.ext.livetv.domain.TvCatalogSelector
import com.nuvio.tv.ext.livetv.domain.model.EpgSource
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategory
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategoryId
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannelRow
import com.nuvio.tv.ext.livetv.domain.model.LiveTvPlayRequest
import com.nuvio.tv.ext.livetv.domain.LiveTvPlayFailure
import com.nuvio.tv.ext.livetv.domain.model.LiveTvStatus
import com.nuvio.tv.ext.livetv.domain.model.LiveTvUiState
import com.nuvio.tv.ext.livetv.domain.model.inCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
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
    catalogRepository: CatalogRepository,
    epgRepository: EpgRepository,
    streamRepository: StreamRepository
) : ViewModel() {

    private val addonRepository = addonRepository
    private val epgRepository = epgRepository
    private val channelLoader = CatalogChannelLoader(catalogRepository)
    private val streamResolver = AddonStreamResolver(streamRepository)

    private val _state = MutableStateFlow(LiveTvUiState())
    val state: StateFlow<LiveTvUiState> = _state.asStateFlow()

    private val _playRequests = Channel<LiveTvPlayRequest>(Channel.BUFFERED)
    val playRequests: Flow<LiveTvPlayRequest> = _playRequests.receiveAsFlow()

    private var catalogs: List<LiveTvCatalog> = emptyList()
    private var installedAddons: List<Addon> = emptyList()
    private var channels: List<LiveTvChannel> = emptyList()
    private var guide: EpgSnapshot = EpgSnapshot.EMPTY
    private var epgSources: List<EpgSource> = emptyList()

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
            installedAddons = addons
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
            epgSources = epgSourcesFor(catalogs)
            publish(
                status = if (channels.isEmpty()) LiveTvStatus.EMPTY else LiveTvStatus.READY,
                message = null,
                failedCatalogs = loaded.failedCatalogs
            )

            if (channels.isEmpty() || epgSources.isEmpty()) return@launch

            // The guide loads after the list is already on screen. A sync downloads tens of megabytes
            // and there is no reason to make the user wait for programming before seeing channels.
            guide = runCatching { epgRepository.sync(epgSources) }
                .getOrNull()
                ?.let { it as? EpgSyncResult.Success }
                ?.snapshot
                ?: guide

            publish(
                status = LiveTvStatus.READY,
                message = null,
                failedCatalogs = loaded.failedCatalogs
            )
        }
    }

    /**
     * Only the addons that actually publish live television.
     *
     * Deriving a guide URL from **every** installed addon is wrong, and the device proved it: a subtitles
     * addon has no `/epg.xml`, so asking it returns a 404 that is reported as "could not be downloaded"
     * and buries the answer for the addon that matters. Scoped to the TV catalogs already selected, the
     * failure list describes reality instead of noise.
     *
     * The third-party country fallbacks stay out for now: reaching an unrelated host on every start needs
     * the disclosure and the opt-out that come with the settings screen (RF-38), and shipping one without
     * the other would be sending the user's IP somewhere they never agreed to. An addon's own guide raises
     * no such question.
     */
    private fun epgSourcesFor(catalogs: List<LiveTvCatalog>): List<EpgSource> =
        catalogs.map { it.addonBaseUrl to it.addonName }
            .distinct()
            .mapNotNull { (baseUrl, addonName) ->
                EpgSourceDiscovery.fromAddon(baseUrl, addonName)
            }

    fun selectCategory(categoryId: LiveTvCategoryId) {
        selectedCategory = categoryId
        publish(status = _state.value.status, message = _state.value.errorMessage)
    }

    /**
     * Resolves the channel's stream and asks the screen to open the player.
     *
     * Failures are typed and surfaced, not swallowed: an addon that answers with no usable URL and an
     * addon that cannot be reached look the same to a user staring at a screen that did nothing.
     */
    fun playChannel(stableKey: String) {
        val channel = channels.firstOrNull { it.stableKey == stableKey } ?: return
        val addon = installedAddons.firstOrNull { it.baseUrl == channel.addonBaseUrl }
            ?: return failWith(LiveTvPlayFailure.RESOLVE_FAILED)

        viewModelScope.launch {
            _state.update { it.copy(resolvingChannelKey = stableKey, playFailure = null) }

            val stream = runCatching { streamResolver.resolve(addon, channel) }
                .getOrNull()

            _state.update { it.copy(resolvingChannelKey = null) }

            if (stream == null) {
                _state.update { it.copy(playFailure = LiveTvPlayFailure.NO_STREAMS) }
                return@launch
            }
            _playRequests.send(LiveTvPlayRequest(channel = channel, stream = stream))
        }
    }

    private fun failWith(failure: LiveTvPlayFailure) {
        _state.update { it.copy(playFailure = failure) }
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
            guide = guide,
            aliases = aliases,
            favorites = favorites,
            nowEpochMs = System.currentTimeMillis()
        )
        val categories = LiveTvRows.categoriesFor(catalogs)

        _state.update { current ->
            current.copy(
                status = status,
                channels = rows,
                categories = categories,
                selectedCategory = selectedCategory,
                totalChannelCount = channels.size,
                guideProgrammeCount = guide.guide.totalProgramsParsed,
                guideLoaded = !guide.isEmpty,
                failedCatalogs = failedCatalogs,
                guideFailure = epgRepository.state.value.lastFailure,
                errorMessage = message
            )
        }
    }
}
