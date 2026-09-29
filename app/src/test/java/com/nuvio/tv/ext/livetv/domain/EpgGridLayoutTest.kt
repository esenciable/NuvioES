package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.ext.livetv.domain.model.LiveTvProgramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgGridLayoutTest {

    private val now = 1_700_000_000_000L
    private val slot = 30L * 60L * 1000L

    @Test
    fun `the window starts a slot before now and runs forward`() {
        val window = EpgGridLayout.windowAround(now, pastSlots = 1, totalSlots = 10)

        assertTrue("the programme on air needs somewhere to begin", window.startEpochMs <= now)
        assertTrue(window.endEpochMs > now)
        assertEquals(10, window.slotCount)
        assertEquals(window.slotMs * 10, window.endEpochMs - window.startEpochMs)
    }

    @Test
    fun `places a programme that sits inside the window`() {
        val window = EpgGridLayout.windowAround(now, pastSlots = 0, totalSlots = 10)

        val cells = EpgGridLayout.cellsFor(
            programmes = listOf(programme("Uno", window.startEpochMs + slot, window.startEpochMs + 3 * slot)),
            window = window
        )

        assertEquals(1, cells.single().startSlot)
        assertEquals(2, cells.single().slotCount)
    }

    @Test
    fun `clamps a programme that started before the window`() {
        // The one on air when the grid opens. It must appear from slot zero, not be dropped.
        // Aligned to the window's own grid, so the expectation does not depend on where in a slot
        // "now" happened to fall.
        val window = EpgGridLayout.windowAround(now, pastSlots = 1, totalSlots = 10)

        val cells = EpgGridLayout.cellsFor(
            programmes = listOf(
                programme("En curso", window.startEpochMs - 5 * slot, window.startEpochMs + 2 * slot)
            ),
            window = window
        )

        assertEquals(0, cells.single().startSlot)
        assertEquals(2, cells.single().slotCount)
    }

    @Test
    fun `clamps a programme that runs past the end of the window`() {
        val window = EpgGridLayout.windowAround(now, pastSlots = 0, totalSlots = 4)

        val cells = EpgGridLayout.cellsFor(
            programmes = listOf(programme("Largo", window.startEpochMs + slot, window.endEpochMs + 10 * slot)),
            window = window
        )

        assertEquals(1, cells.single().startSlot)
        assertEquals("it stops at the window edge", 3, cells.single().slotCount)
        assertEquals(4, cells.single().endSlotExclusive)
    }

    @Test
    fun `drops a programme that does not touch the window`() {
        val window = EpgGridLayout.windowAround(now, pastSlots = 0, totalSlots = 4)

        val cells = EpgGridLayout.cellsFor(
            programmes = listOf(
                programme("Ya terminó", window.startEpochMs - 4 * slot, window.startEpochMs - 3 * slot),
                programme("Muy lejos", window.endEpochMs + slot, window.endEpochMs + 2 * slot),
                programme("Adentro", window.startEpochMs, window.startEpochMs + slot)
            ),
            window = window
        )

        assertEquals(listOf("Adentro"), cells.map { it.programme.title })
    }

    @Test
    fun `a programme shorter than a slot still occupies one`() {
        // A fifteen-minute bulletin in a half-hour grid would otherwise be invisible and unreachable.
        val window = EpgGridLayout.windowAround(now, pastSlots = 0, totalSlots = 10)

        val cells = EpgGridLayout.cellsFor(
            programmes = listOf(programme("Boletín", window.startEpochMs + slot, window.startEpochMs + slot + 5 * 60_000L)),
            window = window
        )

        assertEquals(1, cells.single().startSlot)
        assertEquals(1, cells.single().slotCount)
    }

    @Test
    fun `adjacent programmes do not overlap`() {
        val window = EpgGridLayout.windowAround(now, pastSlots = 0, totalSlots = 10)

        val cells = EpgGridLayout.cellsFor(
            programmes = listOf(
                programme("A", window.startEpochMs, window.startEpochMs + 2 * slot),
                programme("B", window.startEpochMs + 2 * slot, window.startEpochMs + 4 * slot)
            ),
            window = window
        )

        assertEquals(0, cells[0].startSlot)
        assertEquals(2, cells[0].endSlotExclusive)
        assertEquals("B starts exactly where A ends", cells[0].endSlotExclusive, cells[1].startSlot)
    }

    @Test
    fun `sorts by start so the row reads left to right`() {
        val window = EpgGridLayout.windowAround(now, pastSlots = 0, totalSlots = 10)

        val cells = EpgGridLayout.cellsFor(
            programmes = listOf(
                programme("Tercero", window.startEpochMs + 4 * slot, window.startEpochMs + 5 * slot),
                programme("Primero", window.startEpochMs, window.startEpochMs + slot),
                programme("Segundo", window.startEpochMs + 2 * slot, window.startEpochMs + 3 * slot)
            ),
            window = window
        )

        assertEquals(listOf("Primero", "Segundo", "Tercero"), cells.map { it.programme.title })
    }

    @Test
    fun `an empty guide gives an empty row`() {
        val window = EpgGridLayout.windowAround(now)

        assertTrue(EpgGridLayout.cellsFor(emptyList(), window).isEmpty())
    }

    @Test
    fun `the now marker falls in the slot that contains it`() {
        val window = EpgGridLayout.windowAround(now, pastSlots = 1, totalSlots = 10)

        assertEquals(1, EpgGridLayout.slotAt(now, window))
        assertEquals(0, EpgGridLayout.slotAt(window.startEpochMs, window))
        assertNull("before the window", EpgGridLayout.slotAt(window.startEpochMs - 1, window))
        assertNull("after the window", EpgGridLayout.slotAt(window.endEpochMs, window))
    }

    private fun programme(title: String, from: Long, to: Long) = LiveTvProgramme(
        title = title,
        description = null,
        startEpochMs = from,
        stopEpochMs = to
    )
}
