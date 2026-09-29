package com.nuvio.tv.ext.livetv.domain.model

import com.nuvio.tv.ext.livetv.data.epg.EpgFailure

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
    val isFavorite: Boolean
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
    val categories: List<LiveTvCategory> = emptyList(),
    val selectedCategory: LiveTvCategoryId = LiveTvCategoryId.All,
    /** What the details side shows. Null until the user picks a channel. */
    val selectedChannelKey: String? = null,
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
    val errorMessage: String? = null
)
