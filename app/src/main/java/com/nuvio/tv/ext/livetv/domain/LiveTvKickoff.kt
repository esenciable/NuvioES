package com.nuvio.tv.ext.livetv.domain

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The kickoff label a match card shows when the event is not on air yet.
 *
 * Pure so it is testable with an injected zone: the label must always be the DEVICE's timezone --
 * a "20:30" that means a Spanish broadcaster's clock while the viewer sits in Buenos Aires is
 * worse than no badge at all.
 */
object LiveTvKickoff {

    /**
     * The localized time-of-day (e.g. "20:30") of [kickoffIso] in [zone], or null when the addon
     * sent nothing usable. Null -- not a placeholder -- because the card's no-badge look is the
     * documented degradation until the addon starts shipping `released`.
     *
     * Two parse shapes on purpose: a bare ISO instant (`...Z`) and an instant with an explicit
     * offset (`...-03:00`). Which one the addon deploys is not knowable from here, and refusing
     * one of them would silently drop every badge for that shape.
     */
    fun label(
        kickoffIso: String?,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault()
    ): String? {
        val iso = kickoffIso?.takeIf { it.isNotBlank() } ?: return null
        val time = parse(iso, zone) ?: return null
        return formatTime(time, locale)
    }

    /**
     * The day and start time of a not-yet-on-air match, in the DEVICE's zone: "Hoy 20:30" when the
     * match starts today, "Mañana 20:30" when it starts tomorrow, and the weekday abbreviation
     * ("Vie 20:30") for anything further out. The owner asked for exactly this: the time painted
     * inside the addon's SVG card is too small to read from a sofa, so the badge must carry the day
     * too -- a bare "20:30" cannot tell Saturday from Wednesday.
     *
     * Null-safe on the ISO, like [label]: no usable instant means no badge, never a placeholder.
     *
     * The day words are locale-mapped here rather than read from resources because this is a pure
     * domain helper (no Android context) and java.time has no localized relative-day names. The map
     * covers the locales the app ships (en, es, pt); any other locale degrades to the weekday
     * abbreviation, which is still correct -- just less friendly -- instead of leaking English.
     */
    fun startDayAndTime(
        kickoffIso: String?,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault()
    ): String? {
        val time = parse(kickoffIso, zone) ?: return null
        val today = now.atZone(zone).toLocalDate()
        val timeText = formatTime(time, locale)
        val dayText = dayLabel(time.toLocalDate(), today, locale)
        return "$dayText $timeText"
    }

    /**
     * The estimated end of a match, as "~23:30": its kickoff plus the sport's expected duration.
     *
     * The durations are COPIED from the owner's addon (`kino-light-addon/src/sports/catalog.ts`,
     * SPORT_DURATION_MS combined with its SPORTS name table): the addon owns the numbers and the
     * client cannot render an end without them, so until the addon ever exposes them over the wire
     * they live duplicated here. When the addon changes a duration, this table must follow -- the
     * copy source is named so the next reader knows exactly where the truth lives.
     *
     * [now] is accepted to keep the helper family's shape -- callers hand the same instant to both
     * -- and is reserved for a future "already ended" gate; the estimate itself depends only on the
     * kickoff plus the duration. An unknown or blank sport falls back to the addon's own default.
     */
    fun endsAround(
        kickoffIso: String?,
        sportName: String?,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault()
    ): String? {
        val start = parse(kickoffIso, zone) ?: return null
        val end = start.plusMinutes(expectedDurationMinutes(sportName).toLong())
        // The "~" rides with the value: the estimate is approximate by construction, and every
        // locale that ships reads the tilde the same way.
        return "~" + formatTime(end, locale)
    }

    /** The expected duration, in minutes, of a match of [sportName]. Exposed for tests. */
    fun expectedDurationMinutes(sportName: String?): Int {
        val key = sportName?.trim()?.lowercase(Locale.ROOT)
        return if (key != null) SPORT_DURATION_MINUTES[key] ?: DEFAULT_DURATION_MINUTES
        else DEFAULT_DURATION_MINUTES
    }

    /**
     * One shared parser for every entry point: a bare ISO instant (`...Z`) and an instant with an
     * explicit offset (`...-03:00`) are both accepted because which shape the addon deploys is not
     * knowable from here, and refusing one would silently drop every badge for that shape.
     */
    private fun parse(kickoffIso: String?, zone: ZoneId): ZonedDateTime? {
        val iso = kickoffIso?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Instant.parse(iso).atZone(zone) }
            .recoverCatching { OffsetDateTime.parse(iso).atZoneSameInstant(zone) }
            .getOrNull()
    }

    private fun formatTime(time: ZonedDateTime, locale: Locale): String =
        time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))

    /**
     * "Hoy" for today, "Mañana" for tomorrow, the weekday abbreviation otherwise -- falling back to
     * the weekday form for locales the day-word map does not cover.
     */
    private fun dayLabel(day: LocalDate, today: LocalDate, locale: Locale): String {
        val relative = when (day) {
            today -> TODAY_WORDS[locale.language]
            today.plusDays(1) -> TOMORROW_WORDS[locale.language]
            else -> null
        }
        return relative ?: weekdayAbbrev(day, locale)
    }

    /**
     * "Vie"-style weekday: CLDR appends a period in several locales ("vie."), and the owner's
     * example badge is "Vie 20:30", so the dot goes and the first letter is capitalized to match
     * the capitalized relative-day words.
     */
    private fun weekdayAbbrev(day: LocalDate, locale: Locale): String =
        day.format(DateTimeFormatter.ofPattern("EEE", locale))
            .trimEnd('.')
            .replaceFirstChar { it.titlecase(locale) }

    /** Relative-day words per language, keyed by [Locale.language]. See [startDayAndTime]. */
    private val TODAY_WORDS = mapOf("es" to "Hoy", "en" to "Today", "pt" to "Hoje")
    private val TOMORROW_WORDS = mapOf("es" to "Mañana", "en" to "Tomorrow", "pt" to "Amanhã")

    /**
     * Expected match duration in minutes, per sport, copied from the owner's addon
     * (`kino-light-addon/src/sports/catalog.ts`: SPORT_DURATION_MS joined with its SPORTS name
     * table). Keys are the sport names lowercased; the lookup lowercases with [Locale.ROOT] so an
     * uppercased genre from the addon still matches.
     */
    private val SPORT_DURATION_MINUTES = mapOf(
        "fútbol" to 180,
        "básquetbol" to 180,
        "tenis" to 240,
        "béisbol" to 240,
        "críquet" to 480,
        "motor" to 180,
        "rugby" to 150,
        "fútbol americano" to 240,
        "fútbol australiano" to 180,
        "hockey" to 180,
        "bádminton" to 120,
        "vóleibol" to 150,
        "artes marciales" to 180,
        "ciclismo" to 360,
        "golf" to 300,
        "dardos" to 120,
        "atletismo" to 240,
        "otros deportes" to 180
    )

    /** The addon's own fallback duration for a sport its table does not name. */
    const val DEFAULT_DURATION_MINUTES = 240
}
