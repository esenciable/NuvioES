package com.nuvio.tv.ext.livetv.data.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgGuideMergeTest {

    @Test
    fun `an empty list gives an empty guide`() {
        assertTrue(EpgGuideMerge.merge(emptyList()).channels.isEmpty())
        assertTrue(EpgGuideMerge.merge(listOf(XmlTvGuide.EMPTY)).programsByChannelId.isEmpty())
    }

    @Test
    fun `a single guide passes through unchanged`() {
        val guide = guide(
            channels = listOf(channel("c1", "Uno")),
            programs = mapOf("c1" to listOf(program("c1", "A", 0)))
        )

        val merged = EpgGuideMerge.merge(listOf(guide))

        assertEquals(guide, merged)
    }

    @Test
    fun `de-duplicates a channel described by two sources`() {
        // The addon's guide and a country feed routinely describe the same channel. Without this the
        // grid would grow a second identical row per source.
        val first = guide(channels = listOf(channel("c1", "Canal 13")), programs = emptyMap())
        val second = guide(channels = listOf(channel("c1", "Canal 13")), programs = emptyMap())

        val merged = EpgGuideMerge.merge(listOf(first, second))

        assertEquals(1, merged.channels.size)
    }

    @Test
    fun `keeps the icon and every spelling when merging one channel`() {
        val withoutIcon = guide(channels = listOf(channel("c1", "Canal Trece")), programs = emptyMap())
        val withIcon = guide(
            channels = listOf(EpgChannel("c1", listOf("Canal 13"), "https://icon.test/13.png")),
            programs = emptyMap()
        )

        val merged = EpgGuideMerge.merge(listOf(withoutIcon, withIcon)).channels.single()

        assertEquals("https://icon.test/13.png", merged.iconUrl)
        assertEquals(listOf("Canal Trece", "Canal 13"), merged.displayNames)
    }

    @Test
    fun `de-duplicates a programme two sources both describe`() {
        val first = guide(
            channels = listOf(channel("c1", "Uno")),
            programs = mapOf("c1" to listOf(program("c1", "Telenoche", 0)))
        )
        val second = guide(
            channels = listOf(channel("c1", "Uno")),
            programs = mapOf("c1" to listOf(program("c1", "Telenoche", 0)))
        )

        val merged = EpgGuideMerge.merge(listOf(first, second))

        assertEquals(1, merged.programsByChannelId.getValue("c1").size)
    }

    @Test
    fun `keeps a programme that differs only in its time`() {
        val first = guide(
            channels = listOf(channel("c1", "Uno")),
            programs = mapOf("c1" to listOf(program("c1", "Telenoche", 0)))
        )
        val second = guide(
            channels = listOf(channel("c1", "Uno")),
            programs = mapOf("c1" to listOf(program("c1", "Telenoche", HOUR)))
        )

        val merged = EpgGuideMerge.merge(listOf(first, second))

        assertEquals(2, merged.programsByChannelId.getValue("c1").size)
    }

    @Test
    fun `sorts the merged programmes by start`() {
        val first = guide(
            channels = listOf(channel("c1", "Uno")),
            programs = mapOf("c1" to listOf(program("c1", "Tercero", 2 * HOUR)))
        )
        val second = guide(
            channels = listOf(channel("c1", "Uno")),
            programs = mapOf("c1" to listOf(program("c1", "Primero", 0), program("c1", "Segundo", HOUR)))
        )

        val titles = EpgGuideMerge.merge(listOf(first, second))
            .programsByChannelId.getValue("c1").map { it.title }

        assertEquals(listOf("Primero", "Segundo", "Tercero"), titles)
    }

    @Test
    fun `adds up the counters instead of losing them`() {
        val first = XmlTvGuide(emptyList(), emptyMap(), totalProgramsParsed = 3, programsSkippedOutOfWindow = 1)
        val second = XmlTvGuide(emptyList(), emptyMap(), totalProgramsParsed = 4, programsSkippedOutOfWindow = 2)

        val merged = EpgGuideMerge.merge(listOf(first, second))

        assertEquals(7, merged.totalProgramsParsed)
        assertEquals(3, merged.programsSkippedOutOfWindow)
    }

    private fun guide(
        channels: List<EpgChannel>,
        programs: Map<String, List<EpgProgram>>
    ) = XmlTvGuide(
        channels = channels,
        programsByChannelId = programs,
        totalProgramsParsed = programs.values.sumOf { it.size },
        programsSkippedOutOfWindow = 0
    )

    private fun channel(id: String, name: String) = EpgChannel(id, listOf(name), null)

    private fun program(channelId: String, title: String, startOffsetMs: Long) = EpgProgram(
        channelId = channelId,
        title = title,
        description = null,
        startEpochMs = EPOCH + startOffsetMs,
        stopEpochMs = EPOCH + startOffsetMs + HOUR
    )

    private companion object {
        const val EPOCH = 1_000_000L
        const val HOUR = 60L * 60L * 1000L
    }
}
