package com.nuvio.tv.ext.livetv.domain

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
}
