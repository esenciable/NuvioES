package com.nuvio.tv.ext.livetv.magis

import org.json.JSONObject

/**
 * The resolved Magis VOD stream: a direct `{base}/vod/{contentId}_media.{ts|mp4}` URL plus the
 * signed headers the CDN demands on every request. Port of the plugin's `magisResolve` answer.
 */
data class MagisVodStream(
    val url: String,
    val headers: Map<String, String>,
)

/**
 * Native Magis VOD resolution, ported 1:1 from the VERIFIED plugin flow
 * (`esencial-play-providers/flat/magis.js` `getStreams` + `magisResolve`, whose selection
 * heuristics live in [MagisVodSelection]) reusing the M1 core (portal with host failover and
 * rate limit, anonymous device session, per-use runtime config).
 *
 * Call sequence for `resolveDetailed`:
 *  1. TMDB title lookup (fork's own TMDB stack behind [MagisTitleLookup]);
 *  2. `v3/searchByName` (pageSize 10) per title candidate — localized first, original as
 *     fallback — selecting by title COVERAGE with a ±1-year gate and the portal `score` as
 *     tie-breaker (parity fix with the plugin's `magisSelectCandidate`);
 *  3. series only: `v4/getItemData` → the episode's `contentId`;
 *  4. `v10/startPlayVOD` → `episodeList[0]` → best media (h264 > other, mp4 > other) → license;
 *  5. `v14/getSlbInfo` → the `vod` CDN entry with a `free` + `cfl` url → base + auth. This call
 *     is CACHED with the addon's exact semantics ([MagisVodSlbCache]) keyed by the session's
 *     token owner: the addon paid it once per `invalidTime` window, not once per title — that
 *     cache is half the point of this client;
 *  6. final URL `{base}/vod/{contentId}_media.{ts|mp4}` + `Content-Auth`/`Content-License`
 *     headers.
 *
 * Every failure surfaces as a typed [MagisResult] error — never a crash, never a silent empty
 * result. The config is read PER USE (`configProvider`), exactly like [MagisLiveClient]: the
 * remote config can rotate hosts, `apkVer` and the 3DES key while the process lives.
 */
internal class MagisVodClient(
    private val portal: MagisPortalLike,
    private val session: MagisSession,
    /** Runtime config, read PER USE — never a snapshot. */
    private val configProvider: () -> MagisRuntimeConfig,
    private val titleLookup: MagisTitleLookup,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    private val slbCache = MagisVodSlbCache(nowMs)

    /**
     * Resolves [id] (numeric TMDB id or IMDb `tt…` id) into the playable stream. [season] and
     * [episode] only matter for series; the portal selection uses the EPISODE number (the
     * reference ignores the season — `Number(episode) || 0`).
     */
    suspend fun resolveDetailed(
        id: String,
        mediaType: String,
        season: Int = 0,
        episode: Int = 0,
    ): MagisResult<MagisVodStream> {
        if (id.isBlank()) return MagisResult.PortalError(NO_ID, "id vacío para resolver VOD de Magis")
        val isSeries = mediaType.equals("tv", ignoreCase = true) || mediaType.equals("series", ignoreCase = true)

        // 1. Title/year via the fork's TMDB stack — BEFORE minting a session, so an unknown
        //    title costs no portal activation (the plugin answers the same way).
        val info = titleLookup.lookup(id, isSeries)
            ?: return MagisResult.PortalError(NO_TITLE, "TMDB no devolvió título para $id")
        val titles = listOf(info.title, info.originalTitle).filter { it.isNotBlank() }.distinct()

        // A usable anonymous session BEFORE any portal call (same opening as MagisLiveClient):
        // without it the first search would travel with empty userId/userToken.
        val sessionResult = session.ensureAnonymous()
        if (sessionResult !is MagisResult.Ok) return sessionResult.asError()

        // 2. Search per title candidate. A failed search or an empty selection moves on to the
        //    next title (the reference's per-title catch), but when NOTHING worked the last
        //    error surfaces — a resolved flow never ends in a silent empty result.
        var lastError: MagisResult<Nothing>? = null
        var selected: JSONObject? = null
        for (title in titles) {
            val response = session.withValidSession {
                portal.call(
                    path = "v3/searchByName",
                    bean = mapOf(
                        "value" to magisPortalQuery(title),
                        "type" to "0",
                        "columnId" to "",
                        "filter" to "",
                        "pageNum" to 1,
                        "pageSize" to SEARCH_PAGE_SIZE,
                    ),
                    userId = session.userId,
                    userToken = session.userToken,
                )
            }
            val items = response.getOrNull()?.let { magisSearchItems(it) }
            // The year rides on BOTH title attempts (localized and original): the ±1-year gate
            // is what keeps a weak same-name candidate from being accepted blindly.
            val candidate = items?.let { magisSelectCandidate(it, title, isSeries, info.year) }
            if (candidate != null) {
                selected = candidate
                break
            }
            if (response !is MagisResult.Ok) lastError = response.asError()
        }
        val candidate = selected
            ?: return lastError
                ?: MagisResult.PortalError(NO_CANDIDATE, "la búsqueda de Magis no dio candidato para $id")

        var contentId = candidate.flatStr("contentId")
        var seriesContentId = ""
        if (isSeries) {
            // 3. Series: the episode's own contentId.
            seriesContentId = contentId
            val detail = session.withValidSession {
                portal.call(
                    path = "v4/getItemData",
                    bean = mapOf(
                        "contentId" to contentId,
                        "type" to "0",
                        "sortType" to "0",
                        "language" to "en",
                        "macAddr" to MagisDevice.FIXED_MAC,
                    ),
                    userId = session.userId,
                    userToken = session.userToken,
                )
            }
            val detailJson = detail.getOrNull() ?: return detail.asError()
            contentId = magisEpisodeId(detailJson, episode)
                ?: return MagisResult.PortalError(NO_EPISODE, "Magis no trajo el episodio $episode de $contentId")
        }

        // 4. Play session: episodeList[0] → best media → license.
        val play = session.withValidSession {
            portal.call(
                path = "v10/startPlayVOD",
                bean = mapOf(
                    "contentId" to contentId,
                    "seriesContentId" to seriesContentId,
                    "startTime" to 0,
                    "type" to "1",
                    "columnId" to 0,
                    "authType" to "",
                ),
                userId = session.userId,
                userToken = session.userToken,
            )
        }
        val playJson = play.getOrNull() ?: return play.asError()
        val episodes = magisObjects(playJson.optJSONArray("episodeList"))
        val media = episodes.firstOrNull()?.let { magisBestMedia(it) }
            ?: return MagisResult.PortalError(NO_MEDIA, "Magis no dio media reproducible para $contentId")
        val license = magisObjects(media.optJSONArray("licenseList")).firstOrNull()
            ?.flatStr("license").orEmpty()
        if (license.isEmpty()) {
            return MagisResult.PortalError(NO_LICENSE, "Magis dio $contentId sin licencia")
        }

        // 5. SLB (cached): the `vod` CDN entry with a `free` + `cfl` url.
        val tokenOwner = session.userToken
        val slbJson = slbCache.get(tokenOwner) ?: run {
            val slb = session.withValidSession {
                portal.call(
                    path = "v14/getSlbInfo",
                    bean = slbRequestParams(configProvider().apkVersion, liveCodes = listOf(MagisLiveClient.LIVE_ROOT)),
                    userId = session.userId,
                    userToken = session.userToken,
                )
            }
            val slbAnswer = slb.getOrNull() ?: return slb.asError()
            slbCache.put(tokenOwner, slbAnswer)
            slbAnswer
        }
        val cdn = magisVodCdn(slbJson)
            ?: return MagisResult.PortalError(NO_CDN, "no hay entrada CDN cfl de VOD en el slb")

        // 6. Final URL + headers (the reference's exact set).
        val extension = magisVodExtension(media.flatStr("videoFormat"))
        val mediaId = media.flatStr("contentId").ifBlank { contentId }
        return MagisResult.Ok(
            MagisVodStream(
                url = "${cdn.base}/vod/${mediaId}_media.$extension",
                headers = mapOf(
                    "Content-Auth" to cdn.auth,
                    "Content-License" to license,
                    "User-Agent" to VOD_USER_AGENT,
                    "App" to configProvider().appId,
                    "App-Version" to configProvider().apkVersion,
                ),
            ),
        )
    }

    /** Like [resolveDetailed] but `null` on any failure, for callers that only want a URL. */
    suspend fun resolve(id: String, mediaType: String, season: Int = 0, episode: Int = 0): MagisVodStream? =
        (resolveDetailed(id, mediaType, season, episode) as? MagisResult.Ok)?.data

    internal companion object {
        /** The reference's search size (`pageSize: 10`). */
        const val SEARCH_PAGE_SIZE = 10

        /** The CDN identity header the real app sends for VOD (reference `magisResolve`). */
        const val VOD_USER_AGENT = "Ranger/4.9.4-17294ac0"

        const val NO_ID = "vod_no_id"
        const val NO_TITLE = "vod_no_title"
        const val NO_CANDIDATE = "vod_no_candidate"
        const val NO_EPISODE = "vod_no_episode"
        const val NO_MEDIA = "vod_no_media"
        const val NO_LICENSE = "vod_no_license"
        const val NO_CDN = "vod_no_cfl_cdn"
    }
}
