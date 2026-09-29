package com.nuvio.tv.ext.livetv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.ext.livetv.data.AddonStreamResolver
import com.nuvio.tv.ext.livetv.data.CatalogChannelLoader
import com.nuvio.tv.ext.livetv.data.LiveTvPreviewCache
import com.nuvio.tv.ext.livetv.data.LiveTvStore
import com.nuvio.tv.ext.livetv.data.epg.EpgRepository
import com.nuvio.tv.ext.livetv.data.epg.EpgSnapshot
import com.nuvio.tv.ext.livetv.data.epg.EpgSyncResult
import com.nuvio.tv.ext.livetv.domain.AdultChannelFilter
import com.nuvio.tv.ext.livetv.domain.EpgSourceDiscovery
import com.nuvio.tv.ext.livetv.domain.LiveTvCatalog
import com.nuvio.tv.ext.livetv.domain.LiveTvRows
import com.nuvio.tv.ext.livetv.domain.LiveTvZapping
import com.nuvio.tv.ext.livetv.domain.TvCatalogSelector
import com.nuvio.tv.ext.livetv.domain.model.EpgSource
import com.nuvio.tv.ext.livetv.domain.model.EpgSourceOrigin
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategory
import com.nuvio.tv.ext.livetv.domain.model.LiveTvCategoryId
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannelRow
import com.nuvio.tv.ext.livetv.domain.model.LiveTvPlayRequest
import com.nuvio.tv.ext.livetv.domain.model.LiveTvPreview
import com.nuvio.tv.ext.livetv.domain.LiveTvPlayFailure
import com.nuvio.tv.ext.livetv.domain.LiveTvPlayableStream
import com.nuvio.tv.ext.livetv.domain.model.LiveTvStatus
import com.nuvio.tv.ext.livetv.domain.model.LiveTvUiState
import com.nuvio.tv.ext.livetv.domain.model.inCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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
/**
 * Long enough that skimming a list never resolves anything, short enough that settling on a channel
 * feels immediate. The reference fork used 450 ms and started audio at full volume.
 */
private const val PREVIEW_DEBOUNCE_MS = 700L

@OptIn(FlowPreview::class)
@HiltViewModel
class LiveTvViewModel @Inject constructor(
    addonRepository: AddonRepository,
    catalogRepository: CatalogRepository,
    epgRepository: EpgRepository,
    streamRepository: StreamRepository,
    liveTvStore: LiveTvStore
) : ViewModel() {

    private val addonRepository = addonRepository
    private val epgRepository = epgRepository
    private val liveTvStore = liveTvStore
    private val channelLoader = CatalogChannelLoader(catalogRepository)
    private val streamResolver = AddonStreamResolver(streamRepository)
    private val previewCache = LiveTvPreviewCache()

    private val _focusedChannel = MutableStateFlow<String?>(null)

    private val _state = MutableStateFlow(LiveTvUiState())
    val state: StateFlow<LiveTvUiState> = _state.asStateFlow()

    private val _playRequests = Channel<LiveTvPlayRequest>(Channel.BUFFERED)
    val playRequests: Flow<LiveTvPlayRequest> = _playRequests.receiveAsFlow()

    private var catalogs: List<LiveTvCatalog> = emptyList()
    private var installedAddons: List<Addon> = emptyList()
    private var loadedChannels: List<LiveTvChannel> = emptyList()
    /** What every consumer sees: list, preview and playback all read this one, already filtered. */
    private var channels: List<LiveTvChannel> = emptyList()
    private var hideAdultChannels: Boolean = true
    private var guide: EpgSnapshot = EpgSnapshot.EMPTY
    private var epgSources: List<EpgSource> = emptyList()
    private var disabledEpgSourceIds: Set<String> = emptySet()
    /** What the last sync actually fetched, so a settings change only re-fetches when it should. */
    private var lastSyncedEpgSourceIds: List<String> = emptyList()

    /** Favourites arrive with the settings screen; empty means "none marked", not "unknown". */
    private var favorites: Set<String> = emptySet()
    private var selectedCategory: LiveTvCategoryId = LiveTvCategoryId.All

    init {
        refresh()
        observeFocusedChannel()
        observeSettings()
    }

    /**
     * Settings are stored per profile, so they are observed rather than read once: another screen, or
     * another device sharing the profile, can change them.
     */
    private fun observeSettings() {
        viewModelScope.launch {
            liveTvStore.hideAdultChannels.collect { hide ->
                if (hide == hideAdultChannels) return@collect
                hideAdultChannels = hide
                applyAdultFilter()
                publishIfSettled()
            }
        }
        viewModelScope.launch {
            liveTvStore.disabledEpgSourceIds.collect { disabled ->
                if (disabled == disabledEpgSourceIds) return@collect
                disabledEpgSourceIds = disabled
                syncEpgSources()
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
     * Moves the in-screen player down one channel, wrapping at the end.
     *
     * Zapping runs over [channels] -- the list the screen can actually see, already filtered -- so it
     * cannot step onto a channel the parental filter removed or step outside the category. The current
     * channel is the one the player is on, which is the same one focus reports.
     */
    fun nextChannel() {
        zap(step = 1)
    }

    /** Moves the in-screen player up one channel, wrapping at the start. */
    fun previousChannel() {
        zap(step = -1)
    }

    private fun zap(step: Int) {
        val nextKey = LiveTvZapping.neighbourKey(
            channels = channels,
            currentKey = _focusedChannel.value,
            step = step
        ) ?: return
        playChannel(nextKey)
    }

    /**
     * Leaves the in-screen player and goes back to the channel list.
     *
     * Only flips the flag: the channel the player was on stays focused, so the list restores to it and
     * a second OK reopens the same channel without re-resolving anything.
     */
    fun exitFullscreen() {
        _state.update { it.copy(isFullscreen = false) }
    }

    /**
     * Reports which row holds focus, so the preview can follow it.
     *
     * Focus is reported rather than owned: it is not application state, and the composable is the only
     * thing that knows it changed.
     */
    fun onChannelFocused(stableKey: String) {
        _focusedChannel.value = stableKey
    }

    /**
     * Resolves the preview for whatever the user settles on.
     *
     * Debounced, because a preview makes every pause in D-pad movement a stream request. [collectLatest]
     * then abandons the previous resolve as soon as focus moves on, so a fast pass down the list does
     * not leave a queue of requests behind it. The addon runs its own token bucket for exactly this
     * reason, which is a strong hint that the client should not be the one spending that budget.
     */
    private fun observeFocusedChannel() {
        viewModelScope.launch {
            _focusedChannel
                .debounce(PREVIEW_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { stableKey ->
                    if (stableKey != null) resolvePreview(stableKey)
                }
        }
    }

    private suspend fun resolvePreview(stableKey: String) {
        if (stableKey == _state.value.preview?.channelKey) return

        previewCache.get(stableKey)?.let { cached ->
            _state.update { it.copy(preview = cached.toPreview(stableKey), previewFailure = null) }
            return
        }

        val channel = channels.firstOrNull { it.stableKey == stableKey } ?: return
        val addon = installedAddons.firstOrNull { it.baseUrl == channel.addonBaseUrl }
            ?: return failPreviewWith(LiveTvPlayFailure.RESOLVE_FAILED)

        val stream = runCatching { streamResolver.resolve(addon, channel) }.getOrNull()
        if (stream == null) return failPreviewWith(LiveTvPlayFailure.NO_STREAMS)

        previewCache.put(stableKey, stream)
        _state.update { it.copy(preview = stream.toPreview(stableKey), previewFailure = null) }
    }

    private fun failPreviewWith(failure: LiveTvPlayFailure) {
        _state.update { it.copy(preview = null, previewFailure = failure) }
    }

    private fun LiveTvPlayableStream.toPreview(channelKey: String) = LiveTvPreview(
        channelKey = channelKey,
        url = url,
        headers = headers
    )

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
            // The channel set is about to change, so anything resolved for the old one is stale.
            previewCache.clear()
            _state.update { it.copy(preview = null, previewFailure = null) }
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

            loadedChannels = loaded.channels
            applyAdultFilter()
            publish(
                status = if (channels.isEmpty()) LiveTvStatus.EMPTY else LiveTvStatus.READY,
                message = null,
                failedCatalogs = loaded.failedCatalogs
            )

            syncEpgSources()
        }
    }

    /**
     * Re-discovers the guide sources and syncs only when the set actually changed.
     *
     * Idempotent on purpose: the settings observer and the initial load both land here, and a toggle
     * should re-fetch because the user asked for it -- not because two paths happened to run.
     */
    private suspend fun syncEpgSources() {
        if (installedAddons.isEmpty()) return

        // Only the addons that publish a TV catalog. Deriving a guide URL from every installed addon
        // was a bug the device already caught once -- a subtitles addon has no /epg.xml, so asking it
        // returns a 404 that is reported as a failure and buries the answer for the addon that matters.
        val tvAddons = catalogs.map { it.addonBaseUrl to it.addonName }.distinct()

        // The list holds EVERY source, enabled or not. Filtering it here would remove the disabled
        // ones from the settings screen and leave no way to turn them back on.
        epgSources = EpgSourceDiscovery.discover(
            addons = tvAddons,
            userSources = emptyList(),
            disabledIds = emptySet()
        )
        publishIfSettled()

        val enabledSources = epgSources.filterNot { it.id in disabledEpgSourceIds }
        val sourceIds = enabledSources.map { it.id }
        if (sourceIds == lastSyncedEpgSourceIds || enabledSources.isEmpty()) return
        lastSyncedEpgSourceIds = sourceIds

        // The guide loads after the list is already on screen. A sync downloads tens of megabytes and
        // there is no reason to make the user wait for programming before seeing channels.
        guide = runCatching { epgRepository.sync(enabledSources) }
            .getOrNull()
            ?.let { it as? EpgSyncResult.Success }
            ?.snapshot
            ?: guide

        publishIfSettled()
    }

    private fun publishIfSettled() {
        val status = _state.value.status
        if (status == LiveTvStatus.LOADING) return
        publish(status = status, message = _state.value.errorMessage)
    }

    /**
     * The adult filter runs exactly here.
     *
     * Everything downstream reads [channels], so the list, the preview resolver and the play action all
     * see the same filtered set and cannot disagree about it. The reference fork checked in four separate
     * places and still leaked through the handoff to the fullscreen player.
     */
    private fun applyAdultFilter() {
        channels = if (hideAdultChannels) {
            loadedChannels.filterNot(AdultChannelFilter::isAdult)
        } else {
            loadedChannels
        }
    }

    /**
     * The country fallbacks are a third party's servers: on offer, not on by default.
     *
     * They are real value -- they cover channels the addon's own guide misses -- but ten downloads of
     * tens of megabytes and an IP handed to a host unconnected to Nuvio is the user's decision, not a
     * default. The settings screen explains what they are and turns them on one at a time; the addon's
     * own guide needs no such consent.
     */
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

            // What the fullscreen player is on is also what focus reports, so the next zap starts from
            // the channel that is actually playing rather than from wherever the list was left.
            previewCache.put(stableKey, stream)
            _focusedChannel.value = stableKey
            _state.update { it.copy(isFullscreen = true) }
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
                adultFilterActive = hideAdultChannels,
                hiddenChannelCount = loadedChannels.size - channels.size,
                epgSources = epgSources,
                disabledEpgSourceIds = disabledEpgSourceIds,
                guideFailure = epgRepository.state.value.lastFailure,
                errorMessage = message
            )
        }
    }
}
