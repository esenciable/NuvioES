package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel

/**
 * The sport a live match belongs to, derived purely from its genres.
 *
 * The addon is migrating from sending the sport's DISCIPLINE NUMBER as a genre string (e.g. "1")
 * to sending human names like "Fútbol". Both shapes arrive during the transition, and a digit-only
 * genre must never surface as a chip label or a settings row, so it buckets under [OTHER_KEY] --
 * whose display label is the localized "Other/Otros" string, never the stored key.
 */
object LiveTvSports {

    /**
     * Stable store key for matches whose genre is missing, blank, or a bare discipline number.
     * Chosen to be impossible as a real sport name, because it IS the key a toggle persists under.
     */
    const val OTHER_KEY = "__other__"

    /** A sport name the addon would never publish, so it cannot collide with [OTHER_KEY]. */
    private val realSport: (String) -> Boolean = { it.isNotBlank() && it.any { c -> !c.isDigit() } }

    /**
     * The stable key of the sport a match belongs to: its FIRST genre. A match carrying several
     * genres has to pick one for a single-card grid, and the first is the addon's own emphasis.
     */
    fun sportKey(match: LiveTvChannel): String =
        match.genres.firstOrNull()?.trim()?.takeIf(realSport) ?: OTHER_KEY

    /**
     * Every sport key present in the list, sorted by name with the other bucket last, so the
     * settings list is stable regardless of which sport happens to have more matches today.
     */
    fun sportKeysOf(matches: List<LiveTvChannel>): List<String> =
        sortForDisplay(matches.map(::sportKey).toSet())

    /**
     * Sorts stored sport keys for display: alphabetical, [OTHER_KEY] last -- the exact order
     * [sportKeysOf] produces. The settings screen needs this on its own because it reads the sport
     * set from the store (a set carries no order), never from a list of matches.
     */
    fun sortForDisplay(sports: Set<String>): List<String> =
        sports.sortedWith(
            compareBy<String> { it == OTHER_KEY }.thenBy { it }
        )

    /**
     * The chip bar's sports: count descending -- the most-played sport first is the reading order a
     * sports fan expects -- and ties broken by name so the order is deterministic across reloads.
     */
    fun byCount(matches: List<LiveTvChannel>): List<SportCount> =
        matches.groupingBy(::sportKey).eachCount()
            .map { (key, count) -> SportCount(key, count) }
            .sortedWith(compareByDescending<SportCount> { it.count }.thenBy { it.key })

    /**
     * Matches whose sport the user has not turned off, in the addon's own delivery order -- the
     * same order discipline [com.nuvio.tv.ext.livetv.domain.LiveTvPartition] documents.
     */
    fun enabled(matches: List<LiveTvChannel>, disabledSports: Set<String>): List<LiveTvChannel> =
        if (disabledSports.isEmpty()) matches else matches.filterNot { sportKey(it) in disabledSports }

    /** Matches of exactly one sport, for the grid filtered from the chip bar. */
    fun ofSport(matches: List<LiveTvChannel>, sportKey: String): List<LiveTvChannel> =
        matches.filter { sportKey(it) == sportKey }

    data class SportCount(val key: String, val count: Int)
}
