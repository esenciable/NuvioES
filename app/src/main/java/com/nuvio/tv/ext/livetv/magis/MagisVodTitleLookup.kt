package com.nuvio.tv.ext.livetv.magis

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi

/**
 * Title/year lookup port for the Magis VOD flow. The plugin resolves the title with TMDB
 * (`flatTmdbInfo`); the fork ALREADY has the whole TMDB stack ([TmdbService] for the
 * id normalization + imdb↔tmdb conversion with caches, [TmdbApi] for the details), so this
 * port reuses it instead of porting a new TMDB client.
 */
interface MagisTitleLookup {

    /**
     * The localized + original titles for [id] (a numeric TMDB id, `tmdb:`-prefixed, or an
     * IMDb `tt…` id). `null` when TMDB has no entry — the plugin answers the same way and the
     * VOD flow surfaces it as a typed error, never a silent empty result.
     */
    suspend fun lookup(id: String, isSeries: Boolean): MagisTitleInfo?
}

/** The title pair the search fallback walks: localized first, then original. */
data class MagisTitleInfo(val title: String, val originalTitle: String)

/**
 * The fork's TMDB resolution behind [MagisTitleLookup]. `ensureTmdbId` handles every id shape
 * the app produces (`123`, `tmdb:123`, `tt1234567`, with its imdb→tmdb cache) and the details
 * call brings `title`/`original_title` (movie) or `name`/`original_name` (tv), with the same
 * mutual fallback the plugin's `flatDetail` applies.
 */
internal class TmdbTitleLookup(
    private val tmdbService: TmdbService,
    private val tmdbApi: TmdbApi,
) : MagisTitleLookup {

    override suspend fun lookup(id: String, isSeries: Boolean): MagisTitleInfo? {
        val mediaType = if (isSeries) "tv" else "movie"
        val tmdbId = runCatching { tmdbService.ensureTmdbId(id, mediaType) }.getOrNull() ?: return null
        val numericId = tmdbId?.toIntOrNull() ?: return null
        return runCatching {
            val apiKey = tmdbService.apiKey()
            val details = if (isSeries) {
                tmdbApi.getTvDetails(numericId, apiKey).body()
            } else {
                tmdbApi.getMovieDetails(numericId, apiKey).body()
            } ?: return@runCatching null
            // flatDetail's mutual fallback: title takes the original when missing and
            // vice versa, so both fallback candidates are always populated together.
            val title = (details.title ?: details.name ?: details.originalTitle ?: details.originalName)
                ?.trim().orEmpty()
            val original = (details.originalTitle ?: details.originalName ?: title).trim()
            if (title.isEmpty() && original.isEmpty()) return@runCatching null
            MagisTitleInfo(title = title.ifEmpty { original }, originalTitle = original.ifEmpty { title })
        }.getOrNull()
    }
}
