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
import com.nuvio.tv.ext.livetv.domain.LiveTvPartition
import com.nuvio.tv.ext.livetv.domain.LiveTvRows
import com.nuvio.tv.ext.livetv.domain.LiveTvSports
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
import com.nuvio.tv.ext.livetv.domain.model.LiveTvSourcePicker
import com.nuvio.tv.ext.livetv.domain.LiveTvPlayFailure
import com.nuvio.tv.ext.livetv.domain.LiveTvPlayableStream
import com.nuvio.tv.ext.livetv.domain.model.LiveTvStatus
import com.nuvio.tv.ext.livetv.domain.model.LiveTvUiState
import com.nuvio.tv.ext.livetv.domain.model.canBeHidden
import com.nuvio.tv.ext.livetv.domain.model.inCategory
import com.nuvio.tv.ext.livetv.domain.matching
import com.nuvio.tv.ext.livetv.domain.model.preferenceKey
import dagger.hilt.android.lifecycle.HiltViewModel
import android.os.SystemClock
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

    /** Counts playback attempts so every play/retry request is a distinct object (see [LiveTvPlayRequest]). */
    private var playAttempt = 0

    /** When the matches were last published: the freshness clock behind the Partidos TTL. */
    private var matchesLoadedAtMs = 0L

    private var catalogs: List<LiveTvCatalog> = emptyList()
    private var installedAddons: List<Addon> = emptyList()
    private var loadedChannels: List<LiveTvChannel> = emptyList()
    /** What every consumer sees: list, preview and playback all read this one, already filtered. */
    private var channels: List<LiveTvChannel> = emptyList()
    /**
     * The live matches the addon shipped inside the channel catalogs, partitioned out of [channels].
     * Kept raw and in the addon's own delivery order -- the matches view has its own simple layout, and
     * that order is live/priority order. See [LiveTvPartition] for why the split exists.
     */
    private var matches: List<LiveTvChannel> = emptyList()
    private var hideAdultChannels: Boolean = true
    /** The sports whose matches the settings pane turned off. Empty is the default: everything on. */
    private var disabledSports: Set<String> = emptySet()
    /** The last sport set written to the store, so repeated publishes do not hit it again. */
    private var lastKnownSportsWritten: Set<String> = emptySet()
    private var searchQuery: String = ""
    /** Set while the full [refresh] runs, so a second trigger cannot stack two full loads. */
    private var fullRefreshInFlight = false
    private var guide: EpgSnapshot = EpgSnapshot.EMPTY
    private var epgSources: List<EpgSource> = emptyList()
    private var disabledEpgSourceIds: Set<String> = emptySet()
    /** What the last sync actually fetched, so a settings change only re-fetches when it should. */
    private var lastSyncedEpgSourceIds: List<String> = emptyList()

    /** Favourites arrive with the settings screen; empty means "none marked", not "unknown". */
    private var favorites: Set<String> = emptySet()
    private var selectedCategory: LiveTvCategoryId = LiveTvCategoryId.All
    /** The slider categories the user hid, by preference key. Empty means all visible. */
    private var hiddenCategoryIds: Set<String> = emptySet()

    init {
        // No full load here on purpose. Both destinations now trigger their own schedule: Partidos
        // loads only the matches catalog (refreshMatchesOnly) and the channels screen runs the full
        // refresh when it composes over an empty list. Loading all ~57 TV catalogs in init was the
        // measured ~40 s cold start the owner complained about, and it also forced Partidos to wait
        // for data it does not need.
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
                applyPartitionAndFilters()
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
        viewModelScope.launch {
            liveTvStore.disabledSports.collect { disabled ->
                if (disabled == disabledSports) return@collect
                disabledSports = disabled
                applyPartitionAndFilters()
                publishIfSettled()
            }
        }
        viewModelScope.launch {
            liveTvStore.favorites.collect { marked ->
                if (marked == favorites) return@collect
                favorites = marked
                // Republish so both the rows' `isFavorite` flags and the Favorites category's filter
                // rebuild from the new set through the one publish path. A toggle therefore needs no
                // publication of its own: the store flow is the single trigger.
                publishIfSettled()
            }
        }
        viewModelScope.launch {
            liveTvStore.hiddenCategoryIds.collect { hidden ->
                if (hidden == hiddenCategoryIds) return@collect
                hiddenCategoryIds = hidden
                // Hiding the category that is currently selected would leave the slider with no active
                // chip over a list that is still filtered. Fall back to All so the visible state and the
                // filtered list never disagree.
                if (selectedCategory.canBeHidden && selectedCategory.preferenceKey in hidden) {
                    selectCategory(LiveTvCategoryId.All)
                } else {
                    publishIfSettled()
                }
            }
        }
    }

    fun setAdultFilter(hide: Boolean) {
        viewModelScope.launch { liveTvStore.setHideAdultChannels(hide) }
    }

    fun setEpgSourceEnabled(sourceId: String, enabled: Boolean) {
        viewModelScope.launch { liveTvStore.setEpgSourceEnabled(sourceId, enabled) }
    }

    /** Turns a sport's matches on or off in the Partidos section; the store flow re-publishes. */
    fun setSportEnabled(sportKey: String, enabled: Boolean) {
        viewModelScope.launch { liveTvStore.setSportEnabled(sportKey, enabled) }
    }

    /**
     * Stars or unstars one channel; the store flow re-publishes, exactly like [setSportEnabled].
     *
     * The Favorites slider category needs nothing extra to start working: `publish` already filters
     * with `isFavorite = { it.stableKey in favorites }`, so once this field is actually populated the
     * category shows the starred subset on the next publication.
     */
    fun toggleFavorite(stableKey: String) {
        viewModelScope.launch { liveTvStore.setFavorite(stableKey, stableKey !in favorites) }
    }

    /**
     * Shows or hides one category in the slider.
     *
     * `All` and `Favorites` cannot be hidden, so asking to hide them is a no-op here as well as in the
     * pure filter -- one invariant, enforced at both ends.
     */
    fun setCategoryVisible(categoryId: LiveTvCategoryId, visible: Boolean) {
        if (!categoryId.canBeHidden) return
        viewModelScope.launch { liveTvStore.setCategoryVisible(categoryId.preferenceKey, visible) }
    }

    /**
     * Moves the in-screen player down one channel, skipping every source-less channel until one plays.
     *
     * Zapping runs over [channels] -- the list the screen can actually see, already filtered -- so it
     * cannot step onto a channel the parental filter removed or step outside the category. The current
     * channel is the one the player is on, which is the same one focus reports.
     */
    fun nextChannel() {
        zap(step = 1)
    }

    /** Moves the in-screen player up one channel, skipping dead ones the same way. */
    fun previousChannel() {
        zap(step = -1)
    }

    /**
     * Walks [LiveTvZapping.zapCandidates] until a channel resolves.
     *
     * A channel whose stream cannot be resolved must not strand the zap: the user asked to move, so
     * the movement continues to the next candidate. The walk used to be bounded at [MAX_ZAP_SKIPS]
     * dead channels per press (a fixed 3, from the days when only a single neighbour was computed per
     * re-entry) -- but the owner reported being stranded in dead stretches LONGER than that bound: a
     * run of source-less channels simply ate the three skips and the player stayed stuck. The bound is
     * gone, replaced by the wrap-around guarantee of [LiveTvZapping.zapCandidates]: every visible
     * channel in the step direction is tried exactly once, the pass is bounded by the list size by
     * construction, and only when the FULL pass finds nothing does the typed failure surface.
     */
    private fun zap(step: Int) {
        val candidates = LiveTvZapping.zapCandidates(
            channels = channels,
            currentKey = _focusedChannel.value,
            step = step
        )
        // Nothing to move to: the origin is the only channel there is (or the list is empty, which the
        // surface already gates out). Reporting failure here would put an overlay over a channel that
        // is playing fine.
        if (candidates.isEmpty()) return
        walkZap(candidates, index = 0)
    }

    /**
     * Tries candidate [index]; on resolve failure, moves to the next one.
     *
     * [playChannel] keeps the `resolvingChannelKey` feedback alive per candidate, so the spinner walks
     * the list with the attempts. Indexing a frozen list makes the recursion strictly grow, so this
     * cannot loop. Exhausting the pass lands in [failWith] with no continuation: the typed failure is
     * surfaced (the fullscreen overlay picks it up through `playFailure`), which is what the old bound
     * documented but never actually did.
     */
    private fun walkZap(candidates: List<String>, index: Int) {
        if (index == candidates.size) {
            failWith(LiveTvPlayFailure.NO_STREAMS)
            return
        }
        playChannel(
            candidates[index],
            onResolveFailure = { walkZap(candidates, index + 1) }
        )
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

        val channel = findChannel(stableKey) ?: return
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
        // A full load is already walking every catalog; a second one would double the very request
        // count this screen is being optimized against (re-entering the screen re-fires its trigger).
        if (fullRefreshInFlight) return
        fullRefreshInFlight = true
        viewModelScope.launch {
            try {
                _state.update { it.copy(status = LiveTvStatus.LOADING, errorMessage = null) }

                val addons = runCatching { addonRepository.getInstalledAddons().first() }.getOrElse { failure ->
                    publish(
                        status = LiveTvStatus.ERROR,
                        message = failure.message ?: failure::class.java.simpleName,
                        matchesStatus = LiveTvStatus.ERROR
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
                    publish(status = LiveTvStatus.EMPTY, message = null, matchesStatus = LiveTvStatus.EMPTY)
                    return@launch
                }

                val loaded = runCatching { channelLoader.load(catalogs) }.getOrElse { failure ->
                    channels = emptyList()
                    publish(
                        status = LiveTvStatus.ERROR,
                        message = failure.message ?: failure::class.java.simpleName,
                        matchesStatus = LiveTvStatus.ERROR
                    )
                    return@launch
                }

                loadedChannels = loaded.channels
                applyPartitionAndFilters()
                // A sports-only addon (or catalog) delivers nothing but rb_ events, so after the
                // partition an empty TV list no longer means an empty screen: only when BOTH sides
                // came back empty is there genuinely nothing to show.
                val outcome = if (channels.isEmpty() && matches.isEmpty()) LiveTvStatus.EMPTY else LiveTvStatus.READY
                publish(
                    status = outcome,
                    message = null,
                    failedCatalogs = loaded.failedCatalogs,
                    // The matches view mirrors the full load's outcome, so after a full load the two
                    // views can never disagree about what exists.
                    matchesStatus = outcome
                )

                syncEpgSources()
            } finally {
                fullRefreshInFlight = false
            }
        }
    }

    /**
     * Loads ONLY the matches side: the catalogs whose id carries the sports-live fragment, read and
     * published on [LiveTvUiState.matchesStatus] alone.
     *
     * The Partidos section needs exactly one catalog (`esencial-play-sports-live`); waiting for the
     * other ~56 TV catalogs was the ~40 s the owner measured on a cold start. Everything that belongs
     * to the channel list -- its status, its rows, the preview cache, the EPG sync -- is deliberately
     * left alone here: [refresh] owns those.
     *
     * [loadedChannels] IS updated with what this read: the settings observers recompute the matches
     * from that raw load, so skipping it would wipe the grid the moment a sport (or the adult filter)
     * is toggled after a Partidos-only load. The published channel view stays untouched, because only
     * [publish] rebuilds it and this path never calls it.
     */
    fun refreshMatchesOnly() {
        // Matches on screen are LIVE data: events rotate by the minute, so a list loaded once stays
        // wrong as the day moves -- the owner opened Partidos in the afternoon and saw only the
        // football that existed at that first load, hours stale. The section re-fetches on entry
        // when its last load is older than [MATCHES_REFRESH_TTL_MS]; inside the window re-entering
        // does not re-hit the addon (recomposition fires this on every navigation, not every minute).
        // ERROR is deliberately retried -- an error published no matches, and re-entering the section
        // is the only retry it gets short of the full refresh.
        val stale = matchesLoadedAtMs > 0 &&
            SystemClock.elapsedRealtime() - matchesLoadedAtMs >= MATCHES_REFRESH_TTL_MS
        when (_state.value.matchesStatus) {
            LiveTvStatus.READY, LiveTvStatus.EMPTY -> if (!stale) return
            else -> Unit
        }
        viewModelScope.launch {
            _state.update { it.copy(matchesStatus = LiveTvStatus.LOADING) }

            val addons = runCatching { addonRepository.getInstalledAddons().first() }.getOrElse { failure ->
                failMatchesWith(failure.message ?: failure::class.java.simpleName)
                return@launch
            }
            installedAddons = addons
            if (catalogs.isEmpty()) catalogs = TvCatalogSelector.select(addons)

            val matchesCatalogs = catalogs.filter {
                it.catalogId.contains(MATCHES_CATALOG_FRAGMENT, ignoreCase = true)
            }
            if (matchesCatalogs.isEmpty()) {
                // The expected catalog is absent (the addon is not installed, or renamed it). Say EMPTY
                // instead of falling back to every catalog: that fallback would erase the fast path this
                // function exists for, and the channels screen's own trigger still runs the full load,
                // which republishes the matches with everything in them.
                publishMatches(status = LiveTvStatus.EMPTY, matches = emptyList())
                return@launch
            }

            val loaded = runCatching { channelLoader.load(matchesCatalogs) }.getOrElse { failure ->
                failMatchesWith(failure.message ?: failure::class.java.simpleName)
                return@launch
            }

            // A full refresh that started (or finished) while this load ran owns the channel fields;
            // dropping our partial result keeps loadedChannels from shrinking behind its back, and the
            // full load republishes the matches itself.
            if (fullRefreshInFlight || _state.value.status != LiveTvStatus.LOADING) return@launch

            loadedChannels = loaded.channels
            // The same two filters the full load runs, in the same order: the matches grid can never
            // disagree with the settings just because it loaded through the fast path.
            val filtered = if (hideAdultChannels) {
                loadedChannels.filterNot(AdultChannelFilter::isAdult)
            } else {
                loadedChannels
            }
            val partition = LiveTvPartition.split(filtered)
            rememberKnownSports(partition.matches, catalogs)
            val enabled = LiveTvSports.enabled(partition.matches, disabledSports)
            matches = enabled
            publishMatches(
                status = if (enabled.isEmpty()) LiveTvStatus.EMPTY else LiveTvStatus.READY,
                matches = enabled
            )
        }
    }

    /** Publishes only the matches slice; the channel view is [refresh]'s to rebuild. */
    private fun publishMatches(status: LiveTvStatus, matches: List<LiveTvChannel>) {
        // Every successful publication restarts the freshness clock the section re-entry checks --
        // both the fast path and the full load end here.
        matchesLoadedAtMs = SystemClock.elapsedRealtime()
        _state.update { it.copy(matches = matches, matchesStatus = status) }
    }

    private fun failMatchesWith(message: String) {
        _state.update { it.copy(matchesStatus = LiveTvStatus.ERROR, errorMessage = message) }
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
     * The adult filter and the sports filter run exactly here.
     *
     * Everything downstream reads [channels] and [matches], so the list, the matches grid, the
     * preview resolver and the play action all see the same filtered sets and cannot disagree about
     * them. The reference fork checked in four separate places and still leaked through the handoff
     * to the fullscreen player.
     */
    private fun applyPartitionAndFilters() {
        val filtered = if (hideAdultChannels) {
            loadedChannels.filterNot(AdultChannelFilter::isAdult)
        } else {
            loadedChannels
        }
        // The partition runs AFTER the adult filter, on the already-filtered set, so a match can no
        // more reach the UI through the matches view than a channel can through the list. The sports
        // filter runs AFTER the partition, so a disabled sport is gone before anything downstream --
        // grid, chip bar and counts alike -- ever sees it. Everything downstream of this point sees
        // a channel list with NO rb_ event in it and a matches list with no disabled sport in it.
        val partition = LiveTvPartition.split(filtered)
        channels = partition.channels
        rememberKnownSports(partition.matches, catalogs)
        matches = LiveTvSports.enabled(partition.matches, disabledSports)
    }

    /**
     * Publishes the sports the settings screen can list to the store, so the settings screen can
     * show them.
     *
     * The settings ViewModel deliberately never loads channels -- its documented design -- so it
     * cannot derive this list itself; the screen, which HAS the catalogs and the matches, is the
     * publisher, and the store bridges the two exactly as it bridges every preference the pane
     * flips.
     *
     * The set is the UNION of two sources: the sports the addon's manifest declares via its
     * per-sport catalogs ([LiveTvSports.sportsFromCatalogs] -- it exists even when a sport has zero
     * events today, so Settings must offer every sport the addon can publish), and the sports
     * actually observed in the partitioned matches (an addon could ship a live event for a sport its
     * manifest forgot). The set is taken from the partition BEFORE [LiveTvSports.enabled] runs: a
     * disabled sport must stay known, or its settings row would vanish and it could never be turned
     * back on -- the same unreachable toggle the review caught when the rows were derived from the
     * settings state's own matches.
     *
     * Deduplicated like the settings observers dedupe: publish() runs many times between catalog
     * loads, and an identical set must not reach the store again. The field starts empty, so an
     * empty observation at startup is also skipped instead of wiping a remembered list.
     */
    private fun rememberKnownSports(partitionedMatches: List<LiveTvChannel>, liveCatalogs: List<LiveTvCatalog>) {
        val sports = LiveTvSports.sportKeysOf(partitionedMatches).toSet() +
            LiveTvSports.sportsFromCatalogs(liveCatalogs)
        if (sports == lastKnownSportsWritten) return
        lastKnownSportsWritten = sports
        viewModelScope.launch { liveTvStore.rememberSports(sports) }
    }

    /**
     * The country fallbacks are a third party's servers: on offer, not on by default.
     *
     * They are real value -- they cover channels the addon's own guide misses -- but ten downloads of
     * tens of megabytes and an IP handed to a host unconnected to Nuvio is the user's decision, not a
     * default. The settings screen explains what they are and turns them on one at a time; the addon's
     * own guide needs no such consent.
     */
    fun setSearchQuery(query: String) {
        if (query == searchQuery) return
        searchQuery = query
        publishIfSettled()
    }

    fun selectCategory(categoryId: LiveTvCategoryId) {
        selectedCategory = categoryId
        publish(status = _state.value.status, message = _state.value.errorMessage)
        clearPreviewIfChannelLeftCategory()
    }

    /**
     * The preview only makes sense for a channel the list can still show.
     *
     * When the category filter drops the channel the preview is on, keeping it would leave the panel
     * playing something that is no longer reachable from the list -- exactly the state the reference
     * fork left behind. Clearing the focused key as well restarts the debounced feed, so focusing that
     * channel again (or any other) resolves a fresh preview instead of waiting on a value the feed has
     * already seen.
     */
    private fun clearPreviewIfChannelLeftCategory() {
        val visibleKeys = _state.value.channels.mapTo(mutableSetOf()) { it.channel.stableKey }
        val focusedKey = _focusedChannel.value
        val previewKey = _state.value.preview?.channelKey
        val focusedLeft = focusedKey != null && focusedKey !in visibleKeys
        val previewLeft = previewKey != null && previewKey !in visibleKeys
        if (!focusedLeft && !previewLeft) return
        if (focusedLeft) _focusedChannel.value = null
        _state.update { it.copy(preview = null, previewFailure = null) }
    }

    /**
     * Finds a channel or a match by its stable key, across both sides of the partition.
     *
     * Matches live outside [channels] now, but they resolve and play through the exact same addon
     * endpoint -- a match is just another `tv` id to the addon. Blocking rb_ ids here would strand the
     * matches view with buttons that do nothing, so the lookup deliberately covers both lists.
     */
    private fun findChannel(stableKey: String): LiveTvChannel? =
        channels.firstOrNull { it.stableKey == stableKey }
            ?: matches.firstOrNull { it.stableKey == stableKey }

    /**
     * Resolves the channel's stream and asks the screen to open the player.
     *
     * [preResolvedSources] lets a caller that already resolved the full source list (the matches view
     * and its source picker) hand it in instead of forcing a second addon request; when null, this
     * resolves here through [resolveAll], whose first entry is exactly what the old single-stream
     * [resolve] returned, so the channel flow is unchanged. [sourceIndex] selects which of the sources
     * actually plays -- the picker's answer.
     *
     * Failures are typed and surfaced, not swallowed: an addon that answers with no usable URL and an
     * addon that cannot be reached look the same to a user staring at a screen that did nothing.
     */
    fun playChannel(
        stableKey: String,
        preResolvedSources: List<LiveTvPlayableStream>? = null,
        sourceIndex: Int = 0,
        onResolveFailure: (() -> Unit)? = null
    ) {
        val channel = findChannel(stableKey) ?: return
        val addon = installedAddons.firstOrNull { it.baseUrl == channel.addonBaseUrl }
            ?: return failWith(LiveTvPlayFailure.RESOLVE_FAILED, onResolveFailure)

        viewModelScope.launch {
            _state.update { it.copy(resolvingChannelKey = stableKey, playFailure = null) }

            val sources = preResolvedSources
                ?: runCatching { streamResolver.resolveAll(addon, channel) }.getOrNull()

            _state.update { it.copy(resolvingChannelKey = null) }

            if (sources.isNullOrEmpty()) {
                failWith(LiveTvPlayFailure.NO_STREAMS, onResolveFailure)
                return@launch
            }

            startPlayback(channel, sources, sourceIndex.coerceIn(sources.indices))
        }
    }

    /**
     * A match card was clicked: resolve EVERY source, then either play or offer the picker.
     *
     * Exactly one source plays directly -- asking a user to choose between one option is a wasted
     * click. More than one stops at the picker, because the addon's priority order is a hint, not a
     * guarantee, and a sports event's second source is sometimes the only working one.
     */
    fun onMatchClicked(stableKey: String) {
        val channel = findChannel(stableKey) ?: return
        val addon = installedAddons.firstOrNull { it.baseUrl == channel.addonBaseUrl }
            ?: return failWith(LiveTvPlayFailure.RESOLVE_FAILED)

        viewModelScope.launch {
            _state.update { it.copy(resolvingChannelKey = stableKey, playFailure = null) }
            val sources = runCatching { streamResolver.resolveAll(addon, channel) }.getOrDefault(emptyList())
            _state.update { it.copy(resolvingChannelKey = null) }

            when {
                sources.isEmpty() -> failWith(LiveTvPlayFailure.NO_STREAMS)
                sources.size == 1 -> startPlayback(channel, sources, 0)
                else -> _state.update { it.copy(sourcePicker = LiveTvSourcePicker(channel, sources)) }
            }
        }
    }

    /** The user chose a row of the source picker; play THAT source in fullscreen. */
    fun pickSource(index: Int) {
        val picker = _state.value.sourcePicker ?: return
        _state.update { it.copy(sourcePicker = null) }
        viewModelScope.launch {
            startPlayback(picker.channel, picker.sources, index.coerceIn(picker.sources.indices))
        }
    }

    /** Back on the source picker: dismiss it and stay exactly where the user was. */
    fun dismissSourcePicker() {
        _state.update { it.copy(sourcePicker = null) }
    }

    /**
     * Auto-advance: the source at [LiveTvPlayRequest.sourceIndex] fatally failed and further sources
     * exist, so open the next one. Bounded by the request's own source list -- the index strictly
     * grows, so this cannot loop -- and it reuses the already-resolved list rather than re-asking the
     * addon, because the failure was in the STREAM, not in the resolution.
     */
    fun advanceSource(request: LiveTvPlayRequest) {
        val next = request.sourceIndex + 1
        if (next >= request.sources.size) return
        viewModelScope.launch {
            _playRequests.send(
                LiveTvPlayRequest(
                    channel = request.channel,
                    stream = request.sources[next],
                    sources = request.sources,
                    sourceIndex = next,
                    attempt = ++playAttempt
                )
            )
        }
    }

    /**
     * The one path into fullscreen playback. Everything the user can click or auto-advance lands here,
     * so "what is playing" can never disagree between the list, the picker and the error overlay.
     */
    private suspend fun startPlayback(channel: LiveTvChannel, sources: List<LiveTvPlayableStream>, sourceIndex: Int) {
        val stream = sources[sourceIndex]
        // What the fullscreen player is on is also what focus reports, so the next zap starts from
        // the channel that is actually playing rather than from wherever the list was left.
        previewCache.put(channel.stableKey, stream)
        _focusedChannel.value = channel.stableKey
        _state.update { it.copy(isFullscreen = true) }
        _playRequests.send(
            LiveTvPlayRequest(
                channel = channel,
                stream = stream,
                sources = sources,
                sourceIndex = sourceIndex,
                attempt = ++playAttempt
            )
        )
    }

    /**
     * "Reintentar", from the fullscreen error overlay.
     *
     * It re-resolves the channel through the addon instead of replaying the stored URL. A 404 on a live
     * segment usually means the URL in hand is stale -- a rotated playlist or an expired session -- so
     * playing that same URL again reproduces the same failure by construction. A fresh resolve gets a
     * fresh signature, host, and playlist.
     *
     * Failure sends the user back to the list with the typed reason: a fullscreen that cannot resolve
     * has nothing to show, and staying there with the overlay dismissed would be a black dead end.
     */
    fun retryChannel(stableKey: String) {
        val channel = findChannel(stableKey) ?: return
        val addon = installedAddons.firstOrNull { it.baseUrl == channel.addonBaseUrl }
            ?: return exitFullscreenWith(LiveTvPlayFailure.RESOLVE_FAILED)

        viewModelScope.launch {
            val stream = runCatching { streamResolver.resolve(addon, channel) }.getOrNull()
            if (stream == null) return@launch exitFullscreenWith(LiveTvPlayFailure.NO_STREAMS)

            previewCache.put(stableKey, stream)
            _playRequests.send(LiveTvPlayRequest(channel = channel, stream = stream, attempt = ++playAttempt))
        }
    }

    private fun exitFullscreenWith(failure: LiveTvPlayFailure) {
        _state.update { it.copy(isFullscreen = false, playFailure = failure) }
    }

    private fun failWith(failure: LiveTvPlayFailure, onResolveFailure: (() -> Unit)? = null) {
        // A failure with a continuation hands the decision to the caller (the zap walk moves to its
        // next candidate); without one, the typed failure is surfaced as-is.
        if (onResolveFailure != null) onResolveFailure()
        else _state.update { it.copy(playFailure = failure) }
    }

    private companion object {
        /**
         * The id fragment that identifies the matches catalog(s) (`esencial-play-sports-live`). A
         * substring match, not an exact id: the addon may publish variants, and the fast path's whole
         * point is to read every matches catalog and nothing else.
         */
        const val MATCHES_CATALOG_FRAGMENT = "sports-live"

        /**
         * How long a published matches list is trusted before re-entry re-fetches it: live events
         * rotate by the minute, and the fast path made the first load stick for the whole session.
         */
        const val MATCHES_REFRESH_TTL_MS = 5 * 60_000L

    }
    // The fixed per-press dead-channel bound (MAX_ZAP_SKIPS = 3) lived here. It is gone: see [zap]
    // for the device-reported stranding it caused and the full-pass walk that replaced it.

    private fun publish(
        status: LiveTvStatus,
        message: String?,
        failedCatalogs: Int = 0,
        /** The outcome the matches view should mirror; null leaves its current status alone. */
        matchesStatus: LiveTvStatus? = null
    ) {
        val aliases = emptyMap<String, String>()
        // Category and search both run HERE, in the single place the visible channel set is decided.
        //
        // Filtering in the composable instead would let the preview and the zapping reach a channel the
        // list no longer shows -- that is the exact shape of the reference fork's parental-filter leak,
        // and the same reason the adult filter lives here too.
        val visibleChannels = channels
            .inCategory(
                category = LiveTvCategory(selectedCategory),
                isFavorite = { it.stableKey in favorites }
            )
            .matching(searchQuery)
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
                matchesStatus = matchesStatus ?: current.matchesStatus,
                channels = rows,
                matches = matches,
                categories = categories,
                selectedCategory = selectedCategory,
                searchQuery = searchQuery,
                hiddenCategoryIds = hiddenCategoryIds,
                disabledSports = disabledSports,
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
