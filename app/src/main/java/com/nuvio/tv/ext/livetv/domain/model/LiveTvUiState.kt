package com.nuvio.tv.ext.livetv.domain.model

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
    val totalChannelCount: Int = 0,
    val guideProgrammeCount: Int = 0,
    /** How many EPG sources failed on the last sync. Zero is the good case. */
    val failedEpgSources: Int = 0,
    val errorMessage: String? = null
)
