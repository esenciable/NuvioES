package com.nuvio.tv.ext.livetv.domain.model

import com.nuvio.tv.ext.livetv.data.epg.EpgFailure
import com.nuvio.tv.ext.livetv.domain.LiveTvPlayableStream
import com.nuvio.tv.ext.livetv.domain.LiveTvPlayFailure
import com.nuvio.tv.ext.livetv.domain.model.EpgSource

/** One programme placed on the timeline. */
data class LiveTvProgramme(
    val title: String,
    val description: String?,
    val startEpochMs: Long,
    val stopEpochMs: Long
) {
    val durationMs: Long get() = (stopEpochMs - startEpochMs).coerceAtLeast(0L)

    fun isOnAirAt(nowEpochMs: Long): Boolean = nowEpochMs >= startEpochMs && nowEpochMs < stopEpochMs

    fun progressAt(nowEpochMs: Long): Float {
        if (durationMs <= 0L) return if (isOnAirAt(nowEpochMs)) 1f else 0f
        val elapsed = nowEpochMs - startEpochMs
        return (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
}

/**
 * A channel with its guide resolved.
 *
 * [now] and [next] are separate rather than one list because they are the two things the UI shows, and
 * an empty [now] has to be distinguishable from "no guide at all" -- the reference fork rendered both
 * as the same blank row.
 */
data class LiveTvChannelRow(
    val channel: LiveTvChannel,
    val now: LiveTvProgramme?,
    val next: LiveTvProgramme?,
    val isFavorite: Boolean,
    /**
     * Every programme the guide has for this channel inside the window, in order.
     *
     * The grid needs the whole list, not just what is on and what is next, and the row is where the
     * guide resolution already happens.
     */
    val programmes: List<LiveTvProgramme> = emptyList()
) {
    val hasGuide: Boolean get() = now != null || next != null
}

enum class LiveTvStatus {
    /** Reading addons and catalogs. */
    LOADING,

    /** Channels are available. */
    READY,

    /** The addons answered and there are no channels to show. */
    EMPTY,

    /**
     * Something failed. Kept apart from [EMPTY] on purpose: the reference fork turned every network
     * error into "no channels found", so a user offline saw the same screen as a user with an empty
     * catalog and had no way to tell them apart.
     */
    ERROR
}

data class LiveTvUiState(
    val status: LiveTvStatus = LiveTvStatus.LOADING,
    val channels: List<LiveTvChannelRow> = emptyList(),
    /**
     * Live sports matches, kept apart from [channels].
     *
     * The addon delivers matches inside the same `tv` catalogs as real channels (ids prefixed `rb_`),
     * so the partition runs in the ViewModel before anything downstream sees the list: [channels] must
     * contain NO match, or zapping, categories, search and the parental filter would step onto events
     * that are not channels. This list is raw -- unfiltered by category or search -- because the
     * matches view is a flat, simple layout, and it keeps the addon's own delivery order, which is
     * live/priority order.
     */
    val matches: List<LiveTvChannel> = emptyList(),
    val categories: List<LiveTvCategory> = emptyList(),
    val selectedCategory: LiveTvCategoryId = LiveTvCategoryId.All,
    /**
     * What the user typed in the channel search.
     *
     * Lives in the state, and not only in the composable, because the filter runs in one place -- see
     * the ViewModel's publish(). Keeping it here is what stops the list, the grid, the preview and the
     * zapping from disagreeing about which channels exist.
     */
    val searchQuery: String = "",
    /**
     * The slider categories the user removed, by preference key.
     *
     * Carried in state because the slider filters [categories] with it; the settings pane keeps
     * showing **every** category so a hidden one can be turned back on.
     */
    val hiddenCategoryIds: Set<String> = emptySet(),
    val totalChannelCount: Int = 0,
    val guideProgrammeCount: Int = 0,
    /** Whether any guide loaded at all. Distinguishes "nothing tried yet" from "loaded but empty". */
    val guideLoaded: Boolean = false,
    /**
     * Why the guide is missing, when it is. Null means either the guide loaded or nothing was tried --
     * the UI shows programming in the first case and nothing in the second, so the two never need telling
     * apart. Surfaced instead of swallowed: a guide that fails silently is indistinguishable from a guide
     * with nothing to say.
     */
    val guideFailure: EpgFailure? = null,
    /** Catalogs that could not be read. Surfaced instead of swallowed. */
    val failedCatalogs: Int = 0,
    /**
     * Whether the adult filter is applied. On by default.
     *
     * Stated in the UI rather than left implicit, and stated honestly: it is a keyword filter, not a
     * guarantee, and a household that believes otherwise is worse off than one that was told.
     */
    val adultFilterActive: Boolean = true,
    /** How many channels the filter removed, so its effect is visible instead of mysterious. */
    val hiddenChannelCount: Int = 0,
    /** Every guide source there is, so the settings screen can offer them one by one. */
    val epgSources: List<EpgSource> = emptyList(),
    /** Which of those the user turned off. */
    val disabledEpgSourceIds: Set<String> = emptySet(),
    /** The channel whose streams are being resolved, so the row can say so instead of looking stuck. */
    val resolvingChannelKey: String? = null,
    /**
     * Whether the in-screen player is covering the list.
     *
     * Fullscreen lives in our own screen, not in upstream's player, so the screen owns the decision and
     * zapping can read from the same filtered list the list itself shows.
     */
    val isFullscreen: Boolean = false,
    /** Why the last attempt to open a channel failed, if it did. */
    val playFailure: LiveTvPlayFailure? = null,
    /** What the preview panel is playing. Null means nothing resolved (yet, or at all). */
    val preview: LiveTvPreview? = null,
    /** Why the preview is empty when it should not be. */
    val previewFailure: LiveTvPlayFailure? = null,
    /**
     * The source picker waiting for the user's choice, when a channel or match resolved to MORE than
     * one stream. Null means there is nothing to choose: the play pipeline either went straight to the
     * single source or already opened the player. It is an event-shaped piece of state -- set by the
     * resolver, cleared the moment a source is picked or Back dismisses it -- and never published by
     * [publish], which only rebuilds the channel view around it.
     */
    val sourcePicker: LiveTvSourcePicker? = null,
    val errorMessage: String? = null
)

/**
 * A resolved channel with more than one usable source, offered to the user in the addon's own order.
 *
 * The sources are kept as resolved -- no re-ranking -- because the addon already ordered them by
 * priority, and the picker's rows must match what auto-advance will later walk through.
 */
data class LiveTvSourcePicker(
    val channel: LiveTvChannel,
    val sources: List<LiveTvPlayableStream>
)

/**
 * A channel the user asked to watch, with its stream already resolved.
 *
 * Delivered as an event rather than read from [LiveTvUiState]: opening the player is a one-shot action,
 * and a value sitting in state would re-trigger navigation on every recomposition.
 */
data class LiveTvPlayRequest(
    val channel: LiveTvChannel,
    val stream: LiveTvPlayableStream,
    /**
     * Every usable source of the channel, in the addon's own priority order, with [stream] sitting at
     * [sourceIndex]. The fullscreen surface walks this list forward on a fatal playback error
     * (auto-advance) before showing the error overlay, so a match with three dead first sources and a
     * working third one still plays. Empty for requests built without a full resolution -- zapping and
     * the manual retry resolve one stream at a time -- and an empty list simply disables auto-advance.
     */
    val sources: List<LiveTvPlayableStream> = emptyList(),
    /** The position in [sources] of [stream]. Meaningful only when [sources] is not empty. */
    val sourceIndex: Int = 0,
    /**
     * Which playback attempt this is. A retry that re-resolves the SAME stream must still count as a
     * new request: the collector keys work off the request object, and an equal one would be ignored,
     * leaving a dead player on screen. Bumped by the view model on every play and retry.
     */
    val attempt: Int = 0
)

/** What the split-screen panel is showing. */
data class LiveTvPreview(
    val channelKey: String,
    val url: String,
    val headers: Map<String, String>?
)
