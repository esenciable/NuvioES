package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.ext.livetv.domain.model.LiveTvChannel
import java.text.Normalizer

/**
 * Decides whether a channel is adult content.
 *
 * ### What this is, and what it is not
 *
 * It is a **keyword and brand filter**: a considered one, and not a guarantee. Adult channels that avoid
 * every word listed here get through, and the UI says exactly that rather than claiming a block. Calling
 * a keyword list "parental control" without saying so is how a family ends up trusting something that
 * was never going to hold.
 *
 * ### Precision over recall, deliberately
 *
 * A false positive hides a channel somebody wants; a false negative shows content somebody asked not to
 * see. The second is worse, so the list leans towards matching -- but on **whole tokens** rather than
 * substrings, because substring matching is how a filter starts hiding channels its list never intended.
 * `Sussex` must not match `sex`, `Hot News` must not match `hot` (which is why `hot` is not on the list
 * at all), and vague words that double as ordinary channel names are left out on purpose.
 *
 * ### Why it lives in the domain
 *
 * So that **one** place answers this question. The reference fork consulted its detector in four places
 * and still leaked: the handoff to the fullscreen player never asked, so a channel already in the
 * preview could be opened with the filter on. Here the filter is applied once, where the channel list is
 * built, so every consumer -- list, preview and playback -- is downstream of it and structurally cannot
 * disagree.
 */
object AdultChannelFilter {

    /** Whole tokens. Matched against normalised, accent-stripped tokens of equal length. */
    private val TOKENS = setOf(
        "adult", "adults", "adulto", "adultos",
        "porn", "porno", "pornografia", "pornography", "pornhub",
        "xxx", "xxxx",
        "erotic", "erotico", "erotica", "erotik",
        "sex", "sexy", "hardcore", "milf", "hentai",
        "playboy", "penthouse", "hustler", "bluehustler", "brazzers",
        "daring", "dorcel", "sextreme", "redlight", "dorcel"
    )

    /** Compact forms that never appear as their own token. */
    private val PATTERNS = listOf(
        Regex("x{3,}"),
        Regex("\\+18"),
        Regex("18\\+"),
        Regex("18plus")
    )

    /**
     * A mainstream cartoon block whose name contains `adult`. It is the reason token matching alone is
     * not enough: the phrase has to win over the word.
     */
    private val ALLOWED_TOKENS = setOf("adultswim")

    fun isAdult(channel: LiveTvChannel): Boolean =
        isAdultText(channel.name) ||
            channel.genres.any(::isAdultText) ||
            isAdultText(channel.catalogName)

    fun isAdultText(raw: String): Boolean {
        val tokens = tokenize(raw)
        if (tokens.isEmpty()) return false

        if (isAllowed(tokens)) return false
        if (tokens.any { it in TOKENS }) return true

        // Patterns run against a form that KEEPS the '+' sign. Tokenising strips it, so `18+` would
        // never match anything -- the sign is the whole signal in that form.
        return PATTERNS.any { it.containsMatchIn(patternText(raw)) }
    }

    private fun patternText(raw: String): String = Normalizer
        .normalize(raw.trim().lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9+]+"), " ")

    private fun isAllowed(tokens: List<String>): Boolean {
        if (tokens.any { it in ALLOWED_TOKENS }) return true
        // "Adult Swim" plus a suffix like "HD" must still be allowed.
        return tokens.windowed(size = 2).any { it[0] == "adult" && it[1] == "swim" }
    }

    private fun tokenize(raw: String): List<String> {
        val lowered = raw.trim().lowercase()
        val stripped = Normalizer.normalize(lowered, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return stripped.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    }
}
