package com.nuvio.tv.ext.livetv.data.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgGuideIndexTest {

    @Test
    fun `matches by id when the guide shares our ids`() {
        // The addon's own guide is generated from the very channels being listed, so this is the
        // common case: exact, no judgement involved.
        val index = EpgGuideIndex.of(listOf(channel("cyx_610775874141952330898", "Discovery H&H")))

        assertEquals(
            "cyx_610775874141952330898",
            index.findGuideChannelId("cyx_610775874141952330898", "otro nombre cualquiera")
        )
    }

    @Test
    fun `never matches on a substring`() {
        // The reference fork fell back to substring matching in either direction with first-match-wins
        // over an unordered map, so "Globo" bound to "Globonews" or "Globoplay" and the user saw
        // another channel's programming with no sign anything was wrong.
        val index = EpgGuideIndex.of(
            listOf(
                channel("globonews", "Globonews"),
                channel("globoplay", "Globoplay")
            )
        )

        assertNull(index.findGuideChannelId("globoid", "Globo"))
        assertNull("a prefix must not match", index.findGuideChannelId("x", "Globo"))
        assertNull("a superstring must not match", index.findGuideChannelId("y", "Globo HD Internacional"))
    }

    @Test
    fun `matches a normalised name exactly`() {
        val index = EpgGuideIndex.of(listOf(channel("el13", "Canal 13")))

        assertEquals("el13", index.findGuideChannelId("addon:1", "CANAL 13"))
        assertEquals("el13", index.findGuideChannelId("addon:2", "Canal 13 HD"))
        assertEquals("el13", index.findGuideChannelId("addon:3", "Canal-13"))
    }

    @Test
    fun `strips accents before comparing`() {
        val index = EpgGuideIndex.of(listOf(channel("tve", "Televisión Española")))

        assertEquals("tve", index.findGuideChannelId("x", "Television Espanola"))
    }

    @Test
    fun `refuses a name that two different guide channels share`() {
        // Guessing here would bind the user to another channel's programming, which is worse than
        // showing no guide at all.
        val index = EpgGuideIndex.of(
            listOf(
                channel("hbo-1", "HBO"),
                channel("hbo-2", "HBO HD")
            )
        )

        assertNull(index.findGuideChannelId("x", "HBO"))
    }

    @Test
    fun `an explicit alias overrides the name guess`() {
        val index = EpgGuideIndex.of(
            listOf(
                channel("guide-a", "Señal Uno"),
                channel("guide-b", "Señal Dos")
            )
        )

        assertEquals(
            "guide-b",
            index.findGuideChannelId("x", "Señal Uno", aliases = mapOf("senaluno" to "guide-b"))
        )
    }

    @Test
    fun `an alias pointing nowhere is ignored rather than trusted`() {
        val index = EpgGuideIndex.of(listOf(channel("guide-a", "Señal Uno")))

        assertEquals(
            "guide-a",
            index.findGuideChannelId("x", "Señal Uno", aliases = mapOf("senaluno" to "no-existe"))
        )
    }

    @Test
    fun `an empty name never matches`() {
        val index = EpgGuideIndex.of(listOf(channel("guide-a", "Señal Uno")))

        assertNull(index.findGuideChannelId("x", "   "))
        assertNull(index.findGuideChannelId("x", "HD"))
    }

    @Test
    fun `programsFor returns the guide programmes of a matched channel and nothing else`() {
        val index = EpgGuideIndex.of(listOf(channel("guide-a", "Señal Uno")))
        val guide = XmlTvGuide(
            channels = listOf(channel("guide-a", "Señal Uno")),
            programsByChannelId = mapOf(
                "guide-a" to listOf(program("guide-a", "Uno")),
                "guide-b" to listOf(program("guide-b", "Dos"))
            ),
            totalProgramsParsed = 2,
            programsSkippedOutOfWindow = 0
        )

        assertEquals(listOf("Uno"), guide.programsFor(index, "otro-id", "Señal Uno").map { it.title })
        assertTrue(guide.programsFor(index, "otro-id", "No existe").isEmpty())
    }

    @Test
    fun `an empty index matches nothing`() {
        assertTrue(EpgGuideIndex.EMPTY.isEmpty)
        assertNull(EpgGuideIndex.EMPTY.findGuideChannelId("x", "Canal 13"))
    }

    private fun channel(id: String, vararg names: String) =
        EpgChannel(id = id, displayNames = names.toList(), iconUrl = null)

    private fun program(channelId: String, title: String) = EpgProgram(
        channelId = channelId,
        title = title,
        description = null,
        startEpochMs = 1_000L,
        stopEpochMs = 2_000L
    )
}
