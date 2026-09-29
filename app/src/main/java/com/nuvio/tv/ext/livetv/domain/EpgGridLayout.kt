package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.ext.livetv.domain.model.LiveTvProgramme

/**
 * The slice of time the grid shows, and how it is divided.
 *
 * [slotMinutes] is the grid's resolution: a programme is placed by which slot it starts in, not by
 * pixels. Keeping the arithmetic in slots rather than in pixels means the whole thing can be tested
 * without a screen, a density or a layout pass.
 */
data class EpgGridWindow(
    val startEpochMs: Long,
    val endEpochMs: Long,
    val slotMinutes: Int,
    val slotWidthDp: Int
) {
    val slotMs: Long get() = slotMinutes * 60_000L

    val slotCount: Int get() = ((endEpochMs - startEpochMs) / slotMs).toInt()
}

/**
 * One programme, placed on the grid.
 *
 * [slotCount] is never zero: a programme shorter than a slot would otherwise be invisible, and a
 * fifteen-minute bulletin in a half-hour grid is a normal thing to find.
 */
data class EpgGridCell(
    val programme: LiveTvProgramme,
    val startSlot: Int,
    val slotCount: Int
) {
    val endSlotExclusive: Int get() = startSlot + slotCount
}

/**
 * Places programmes on a time grid.
 *
 * Pure, so the edges are testable without a device: a programme that starts before the window begins,
 * one that runs past its end, one shorter than a slot, and one that does not intersect the window at
 * all. Those are exactly the cases a hand-written grid gets wrong, and the reference fork's grid was
 * built inside the composable where none of them could be checked.
 */
object EpgGridLayout {

    /**
     * A window that starts slightly before now -- so the programme currently on air has somewhere to
     * begin -- and runs forward.
     */
    fun windowAround(
        nowEpochMs: Long,
        pastSlots: Int = DEFAULT_PAST_SLOTS,
        totalSlots: Int = DEFAULT_TOTAL_SLOTS,
        slotMinutes: Int = DEFAULT_SLOT_MINUTES,
        slotWidthDp: Int = DEFAULT_SLOT_WIDTH_DP
    ): EpgGridWindow {
        val slotMs = slotMinutes * 60_000L
        val alignedNow = (nowEpochMs / slotMs) * slotMs
        val start = alignedNow - pastSlots * slotMs
        return EpgGridWindow(
            startEpochMs = start,
            endEpochMs = start + totalSlots * slotMs,
            slotMinutes = slotMinutes,
            slotWidthDp = slotWidthDp
        )
    }

    fun cellsFor(programmes: List<LiveTvProgramme>, window: EpgGridWindow): List<EpgGridCell> {
        if (window.slotMs <= 0L || window.slotCount <= 0) return emptyList()

        return programmes.mapNotNull { programme ->
            val visibleStart = maxOf(programme.startEpochMs, window.startEpochMs)
            val visibleEnd = minOf(programme.stopEpochMs, window.endEpochMs)

            // No overlap with the window at all.
            if (visibleEnd <= visibleStart) return@mapNotNull null

            val startSlot = ((visibleStart - window.startEpochMs) / window.slotMs)
                .toInt()
                .coerceIn(0, window.slotCount - 1)
            val endSlot = ((visibleEnd - window.startEpochMs + window.slotMs - 1) / window.slotMs)
                .toInt()
                .coerceIn(0, window.slotCount)

            EpgGridCell(
                programme = programme,
                startSlot = startSlot,
                // A programme shorter than a slot still occupies one, or it would never be seen.
                slotCount = (endSlot - startSlot).coerceAtLeast(1)
            )
        }.sortedBy { it.startSlot }
    }

    /** The slot containing [nowEpochMs], or null when now is outside the window. */
    fun slotAt(nowEpochMs: Long, window: EpgGridWindow): Int? {
        if (window.slotMs <= 0L) return null
        val offset = nowEpochMs - window.startEpochMs
        if (offset < 0L || nowEpochMs >= window.endEpochMs) return null
        return (offset / window.slotMs).toInt()
    }

    const val DEFAULT_SLOT_MINUTES = 30
    const val DEFAULT_PAST_SLOTS = 1
    const val DEFAULT_TOTAL_SLOTS = 10
    const val DEFAULT_SLOT_WIDTH_DP = 160
}
