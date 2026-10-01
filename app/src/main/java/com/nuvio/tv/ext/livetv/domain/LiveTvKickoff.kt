package com.nuvio.tv.ext.livetv.domain

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
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
        val time = runCatching { Instant.parse(iso).atZone(zone) }
            .recoverCatching { OffsetDateTime.parse(iso).atZoneSameInstant(zone) }
            .getOrNull() ?: return null
        return time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    }
}
