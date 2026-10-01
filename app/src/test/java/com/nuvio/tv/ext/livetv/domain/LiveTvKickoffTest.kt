package com.nuvio.tv.ext.livetv.domain

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The kickoff badge is a pure formatting problem: an ISO instant in, a localized device-time label
 * out, null when the input cannot be understood. Deterministic because the zone is injected -- the
 * device never chooses the zone a test runs under.
 */
class LiveTvKickoffTest {

    private val buenosAires = ZoneId.of("America/Argentina/Buenos_Aires")

    /** Locale.ROOT formats with a 24-hour clock, so the assertions do not depend on the test JVM's locale. */
    private val locale = Locale.ROOT

    /** 24-hour English: Locale.ENGLISH itself formats SHORT times as "8:30 PM". */
    private val english24h = Locale.UK

    private val spanish = Locale("es")

    @Test
    fun `formats an ISO instant in the given zone`() {
        val label = LiveTvKickoff.label("2026-02-05T23:30:00Z", zone = buenosAires, locale = locale)

        // 23:30Z is 20:30 in UTC-3.
        assertEquals("20:30", label)
    }

    @Test
    fun `formats an instant carrying an explicit offset`() {
        val label = LiveTvKickoff.label("2026-02-05T20:30:00-03:00", zone = buenosAires, locale = locale)

        assertEquals("20:30", label)
    }

    @Test
    fun `unparsable input is null rather than a crash`() {
        assertNull(LiveTvKickoff.label("proximamente", zone = buenosAires, locale = locale))
        assertNull(LiveTvKickoff.label("", zone = buenosAires, locale = locale))
        assertNull(LiveTvKickoff.label(null, zone = buenosAires, locale = locale))
        // A bare date has no time of day to promise.
        assertNull(LiveTvKickoff.label("2026-02-05", zone = buenosAires, locale = locale))
    }

    /*
     * startDayAndTime: the day the match starts rides with the time, because a bare "20:30" cannot
     * tell Saturday from Wednesday. All instants below are 23:30Z, which is 20:30 in UTC-3.
     */

    @Test
    fun `same-day kickoff reads Today`() {
        // 2026-02-06 is a Friday; 20:00Z is 17:00 in Buenos Aires, so the kickoff day IS today.
        val now = Instant.parse("2026-02-06T20:00:00Z")

        // Locale.ROOT carries no language, so it takes the weekday fallback by design; the English
        // day word needs a real English locale.
        assertEquals(
            "Today 20:30",
            LiveTvKickoff.startDayAndTime(
                "2026-02-06T23:30:00Z", now, zone = buenosAires, locale = english24h
            )
        )
        // The owner's example is Spanish: the same input under the es locale reads "Hoy".
        assertEquals(
            "Hoy 20:30",
            LiveTvKickoff.startDayAndTime(
                "2026-02-06T23:30:00Z", now, zone = buenosAires, locale = spanish
            )
        )
    }

    @Test
    fun `next-day kickoff reads Tomorrow`() {
        // Now is Thursday Feb 5 in Buenos Aires; the kickoff lands on Friday Feb 6.
        val now = Instant.parse("2026-02-05T22:00:00Z")

        assertEquals(
            "Tomorrow 20:30",
            LiveTvKickoff.startDayAndTime(
                "2026-02-06T23:30:00Z", now, zone = buenosAires, locale = english24h
            )
        )
        assertEquals(
            "Mañana 20:30",
            LiveTvKickoff.startDayAndTime(
                "2026-02-06T23:30:00Z", now, zone = buenosAires, locale = spanish
            )
        )
    }

    @Test
    fun `mid-week kickoff reads the weekday abbreviation`() {
        // Now is Tuesday Feb 3; the kickoff lands on Friday Feb 6.
        val now = Instant.parse("2026-02-03T22:00:00Z")

        assertEquals(
            "Fri 20:30",
            LiveTvKickoff.startDayAndTime(
                "2026-02-06T23:30:00Z", now, zone = buenosAires, locale = english24h
            )
        )
        // CLDR writes the Spanish short weekday as "vie."; the owner's example is "Vie 20:30",
        // so the period is stripped and the letter capitalized.
        assertEquals(
            "Vie 20:30",
            LiveTvKickoff.startDayAndTime(
                "2026-02-06T23:30:00Z", now, zone = buenosAires, locale = spanish
            )
        )
    }

    @Test
    fun `startDayAndTime is null when the addon sent nothing usable`() {
        val now = Instant.parse("2026-02-05T22:00:00Z")

        assertNull(LiveTvKickoff.startDayAndTime(null, now, zone = buenosAires, locale = locale))
        assertNull(LiveTvKickoff.startDayAndTime("", now, zone = buenosAires, locale = locale))
        assertNull(
            LiveTvKickoff.startDayAndTime("proximamente", now, zone = buenosAires, locale = locale)
        )
    }

    /*
     * endsAround: kickoff plus the sport's copied duration, rendered as an estimate.
     */

    @Test
    fun `endsAround adds the sport's duration to the kickoff`() {
        val now = Instant.parse("2026-02-05T22:00:00Z")

        // Fútbol is 180 minutes: 20:30 + 3h = 23:30, the owner's example.
        assertEquals(
            "~23:30",
            LiveTvKickoff.endsAround(
                "2026-02-06T23:30:00Z", "Fútbol", now, zone = buenosAires, locale = locale
            )
        )
    }

    @Test
    fun `unknown or blank sport falls back to the addon default of 240 minutes`() {
        val now = Instant.parse("2026-02-05T22:00:00Z")

        // 20:30 + 4h crosses midnight: 00:30 on the next day.
        assertEquals(
            "~00:30",
            LiveTvKickoff.endsAround(
                "2026-02-06T23:30:00Z", "Cabalgata", now, zone = buenosAires, locale = locale
            )
        )
        assertEquals(
            "~00:30",
            LiveTvKickoff.endsAround(
                "2026-02-06T23:30:00Z", "   ", now, zone = buenosAires, locale = locale
            )
        )
        assertEquals(
            "~00:30",
            LiveTvKickoff.endsAround(
                "2026-02-06T23:30:00Z", null, now, zone = buenosAires, locale = locale
            )
        )
    }

    @Test
    fun `duration lookup covers the addon table and is case-insensitive`() {
        assertEquals(180, LiveTvKickoff.expectedDurationMinutes("Fútbol"))
        assertEquals(180, LiveTvKickoff.expectedDurationMinutes("FÚTBOL"))
        assertEquals(480, LiveTvKickoff.expectedDurationMinutes("Críquet"))
        assertEquals(360, LiveTvKickoff.expectedDurationMinutes("Ciclismo"))
        assertEquals(180, LiveTvKickoff.expectedDurationMinutes("Otros Deportes"))
        assertEquals(240, LiveTvKickoff.expectedDurationMinutes("Cabalgata"))
        assertEquals(240, LiveTvKickoff.expectedDurationMinutes(""))
        assertEquals(240, LiveTvKickoff.expectedDurationMinutes(null))
    }

    @Test
    fun `endsAround is null when the addon sent nothing usable`() {
        val now = Instant.parse("2026-02-05T22:00:00Z")

        assertNull(
            LiveTvKickoff.endsAround(null, "Fútbol", now, zone = buenosAires, locale = locale)
        )
        assertNull(
            LiveTvKickoff.endsAround("proximamente", "Fútbol", now, zone = buenosAires, locale = locale)
        )
    }
}
