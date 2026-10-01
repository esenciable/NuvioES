package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sport a match belongs to is derived from its first genre, with an explicit "other" bucket:
 * the addon historically sent the sport's DISCIPLINE NUMBER as a string (e.g. "1"), so a digit-only
 * or missing genre must never surface as a chip or a settings row.
 */
class LiveTvSportsTest {

    @Test
    fun `the first genre is the sport`() {
        val match = match(genres = listOf("Fútbol", "Tenis"))

        assertEquals("Fútbol", LiveTvSports.sportKey(match))
    }

    @Test
    fun `a digit-only genre is the other bucket, not a sport name`() {
        // The addon used to send the discipline NUMBER as a string. A chip or a settings row that
        // says "1" is noise, so it buckets under the stable other key.
        assertEquals(LiveTvSports.OTHER_KEY, LiveTvSports.sportKey(match(genres = listOf("1"))))
        assertEquals(LiveTvSports.OTHER_KEY, LiveTvSports.sportKey(match(genres = listOf(" 12 "))))
    }

    @Test
    fun `a blank or missing genre is the other bucket too`() {
        assertEquals(LiveTvSports.OTHER_KEY, LiveTvSports.sportKey(match(genres = listOf("   "))))
        assertEquals(LiveTvSports.OTHER_KEY, LiveTvSports.sportKey(match(genres = emptyList())))
    }

    @Test
    fun `sport keys are the distinct sports, sorted by name, with other last`() {
        val matches = listOf(
            match(genres = listOf("Tenis")),
            match(genres = listOf("Fútbol")),
            match(genres = listOf("Fútbol")),
            match(genres = listOf("1")),
            match(genres = emptyList())
        )

        assertEquals(
            listOf("Fútbol", "Tenis", LiveTvSports.OTHER_KEY),
            LiveTvSports.sportKeysOf(matches)
        )
    }

    @Test
    fun `the chip bar orders by count descending and then by name`() {
        val matches = listOf(
            match(genres = listOf("Tenis")),
            match(genres = listOf("Baloncesto")),
            match(genres = listOf("Fútbol")),
            match(genres = listOf("Fútbol")),
            match(genres = listOf("Fútbol"))
        )

        assertEquals(
            listOf(
                LiveTvSports.SportCount("Fútbol", 3),
                LiveTvSports.SportCount("Baloncesto", 1),
                LiveTvSports.SportCount("Tenis", 1)
            ),
            LiveTvSports.byCount(matches)
        )
    }

    @Test
    fun `enabled keeps the addon's order and drops disabled sports`() {
        val matches = listOf(
            match(id = "rb_1", genres = listOf("Fútbol")),
            match(id = "rb_2", genres = listOf("Tenis")),
            match(id = "rb_3", genres = listOf("Fútbol"))
        )

        assertEquals(
            listOf("rb_1", "rb_3"),
            LiveTvSports.enabled(matches, disabledSports = setOf("Tenis")).map { it.id }
        )
        // Empty disabled set is the default: everything on.
        assertEquals(
            listOf("rb_1", "rb_2", "rb_3"),
            LiveTvSports.enabled(matches, disabledSports = emptySet()).map { it.id }
        )
    }

    @Test
    fun `disabling the other bucket removes the genreless matches`() {
        val matches = listOf(
            match(id = "rb_1", genres = listOf("1")),
            match(id = "rb_2", genres = listOf("Fútbol"))
        )

        assertEquals(
            listOf("rb_2"),
            LiveTvSports.enabled(matches, disabledSports = setOf(LiveTvSports.OTHER_KEY)).map { it.id }
        )
    }

    @Test
    fun `ofSport keeps only that sport, preserving order`() {
        val matches = listOf(
            match(id = "rb_1", genres = listOf("Fútbol")),
            match(id = "rb_2", genres = listOf("Tenis")),
            match(id = "rb_3", genres = listOf("Fútbol"))
        )

        assertEquals(
            listOf("rb_1", "rb_3"),
            LiveTvSports.ofSport(matches, "Fútbol").map { it.id }
        )
        // The other bucket filters by the same stable key it is derived with.
        assertEquals(
            emptyList<String>(),
            LiveTvSports.ofSport(matches, LiveTvSports.OTHER_KEY).map { it.id }
        )
    }

    private fun match(
        id: String = "rb_1",
        genres: List<String>
    ) = LiveTvChannel(
        id = id,
        addonBaseUrl = "https://addon.test/token",
        addonName = "Esencial Play",
        catalogIds = setOf("vivo"),
        catalogName = "En vivo",
        apiType = "tv",
        name = "Partido $id",
        logoUrl = null,
        posterUrl = null,
        description = null,
        genres = genres
    )
}
