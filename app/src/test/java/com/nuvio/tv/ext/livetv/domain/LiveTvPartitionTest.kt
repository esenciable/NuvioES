package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvPartitionTest {

    @Test
    fun `a channel whose id starts with rb_ is a match`() {
        assertTrue(LiveTvPartition.isMatch(channel(id = "rb_12345_futbol")))
    }

    @Test
    fun `a real channel is not a match even when the name mentions sports`() {
        assertFalse(LiveTvPartition.isMatch(channel(id = "espn", name = "ESPN Deportes")))
    }

    @Test
    fun `a mixed input yields channels without rb_ and matches only rb_`() {
        val tv1 = channel(id = "espn")
        val match1 = channel(id = "rb_100_futbol", name = "EN VIVO: TeamA vs. TeamB")
        val tv2 = channel(id = "fox")
        val match2 = channel(id = "rb_200_basquet", name = "EN VIVO: TeamC vs. TeamD")

        val partition = LiveTvPartition.split(listOf(tv1, match1, tv2, match2))

        assertEquals(listOf(tv1, tv2), partition.channels)
        assertEquals(listOf(match1, match2), partition.matches)
    }

    @Test
    fun `the addon's own order is preserved on both sides`() {
        // The delivery order IS priority order for channels and live order for matches; re-sorting it
        // here would second-guess the addon's ranking.
        val channels = listOf(
            channel(id = "c3"),
            channel(id = "rb_1_a"),
            channel(id = "c1"),
            channel(id = "rb_2_b"),
            channel(id = "c2")
        )

        val partition = LiveTvPartition.split(channels)

        assertEquals(listOf("c3", "c1", "c2"), partition.channels.map { it.id })
        assertEquals(listOf("rb_1_a", "rb_2_b"), partition.matches.map { it.id })
    }

    @Test
    fun `an all-channels input leaves the matches list empty`() {
        val partition = LiveTvPartition.split(listOf(channel(id = "c1"), channel(id = "c2")))

        assertTrue(partition.matches.isEmpty())
        assertEquals(2, partition.channels.size)
    }

    @Test
    fun `an all-matches input leaves the channel list empty`() {
        val partition = LiveTvPartition.split(listOf(channel(id = "rb_1_futbol")))

        assertTrue(partition.channels.isEmpty())
        assertEquals(1, partition.matches.size)
    }

    @Test
    fun `an empty input splits into two empty lists`() {
        val partition = LiveTvPartition.split(emptyList())

        assertTrue(partition.channels.isEmpty())
        assertTrue(partition.matches.isEmpty())
    }

    @Test
    fun `an id that merely contains rb_ is still a channel`() {
        // Only the PREFIX marks a match: a channel id carrying rb_ mid-string is a real channel.
        assertFalse(LiveTvPartition.isMatch(channel(id = "xxrb_1")))
    }

    private fun channel(id: String, name: String = "Canal $id") = LiveTvChannel(
        id = id,
        addonBaseUrl = "https://addon.test/token",
        addonName = "Esencial Play",
        catalogIds = setOf("vivo"),
        catalogName = "En vivo",
        apiType = "tv",
        name = name,
        logoUrl = null,
        posterUrl = null,
        description = null,
        genres = emptyList()
    )
}
