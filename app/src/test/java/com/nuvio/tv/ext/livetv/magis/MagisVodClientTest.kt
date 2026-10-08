package com.nuvio.tv.ext.livetv.magis

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The VOD resolution flow against a scripted portal (no network): the exact call sequence, the
 * title fallback, the typed errors and the SLB cache reuse — the contract the plugin verifies
 * against the real portal today.
 */
class MagisVodClientTest {

    /** In-memory session store (the real one is DataStore-backed and needs an Android context). */
    private class FakeStore : MagisSessionStore {
        var current: StoredSession? = null
        override fun save(s: StoredSession) { current = s }
        override fun read(): StoredSession? = current
    }

    /** A scripted [MagisTitleLookup]: returns [info] for any id. */
    private class FakeTitleLookup(
        private val info: MagisTitleInfo? = MagisTitleInfo(title = "Coco", originalTitle = "Coco"),
    ) : MagisTitleLookup {
        var lastId: String? = null
        var lastIsSeries: Boolean? = null
        override suspend fun lookup(id: String, isSeries: Boolean): MagisTitleInfo? {
            lastId = id
            lastIsSeries = isSeries
            return info
        }
    }

    /** Scripted portal: per-path answers, recording every (path, bean) pair. */
    private class FakePortal(
        private val script: MutableMap<String, MagisResult<JSONObject>.() -> MagisResult<JSONObject>> = mutableMapOf(),
    ) : MagisPortalLike {
        data class Call(val path: String, val bean: Map<String, Any?>)

        val calls = mutableListOf<Call>()

        override suspend fun call(
            path: String,
            bean: Map<String, Any?>,
            baseFields: Boolean,
            userId: String,
            userToken: String,
            sn: String?,
        ): MagisResult<JSONObject> {
            calls += Call(path, bean)
            val default: MagisResult<JSONObject>.() -> MagisResult<JSONObject> = {
                MagisResult.Ok(JSONObject(mapOf("userId" to "u1", "userToken" to "t1", "snToken" to "ST-fake")))
            }
            return (script[path] ?: default).invoke(MagisResult.Ok(JSONObject()))
        }
    }

    private val config = MagisRuntimeConfig(
        hosts = listOf("host1.test"),
        appId = "com.android.msandroid",
        apkVersion = "49902",
        apkVerHeader = "43404",
        spkgVer = "2025-08-07 05:40:11_36_16_",
        threeDesKeyHex = "e7af1ed7de1ffddd7bd3fe37ebdffde9ef3fe1ae39edfeb8",
    )

    private fun searchItem(contentId: String, name: String, programType: String = "") = JSONObject()
        .put("contentId", contentId).put("name", name).put("programType", programType)

    private fun searchAnswer(vararg items: JSONObject) = MagisResult.Ok(
        JSONObject().put("searchItem", JSONArray().apply { items.forEach { put(it) } }),
    )

    private fun itemDataAnswer(vararg episodes: Pair<Int, String>) = MagisResult.Ok(
        JSONObject().put(
            "assetData",
            JSONObject().put(
                "simpleProgramList",
                JSONArray().apply { episodes.forEach { (n, id) -> put(JSONObject().put("seriesNumber", n).put("contentId", id)) } },
            ),
        ),
    )

    private fun playAnswer(mediaContentId: String, videoFormat: String, license: String = "lic-1") = MagisResult.Ok(
        JSONObject().put(
            "episodeList",
            JSONArray().put(
                JSONObject().put(
                    "totalMovieList",
                    JSONArray().put(
                        JSONObject().put(
                            "movieList",
                            JSONArray().put(
                                JSONObject()
                                    .put("contentId", mediaContentId)
                                    .put("encodeFormat", "h264")
                                    .put("videoFormat", videoFormat)
                                    .put("height", 1080)
                                    .put("licenseList", JSONArray().put(JSONObject().put("license", license))),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun slbAnswer(mainAddr: String = "cdn.example.com/") = MagisResult.Ok(
        JSONObject()
            .put("invalidTime", 14400)
            .put(
                "cdn_list",
                JSONArray().put(
                    JSONObject()
                        .put("tag", "vod")
                        .put("main_addr", mainAddr)
                        .put("url_list", JSONArray().put(JSONObject().put("url", "a=1&sign_type=cfl").put("tag", "free"))),
                ),
            ),
    )

    private fun client(
        portal: FakePortal,
        store: FakeStore = FakeStore(),
        lookup: MagisTitleLookup = FakeTitleLookup(),
    ): MagisVodClient {
        val session = MagisSession(portal, store)
        return MagisVodClient(
            portal = portal,
            session = session,
            configProvider = { config },
            titleLookup = lookup,
        )
    }

    // --- happy paths ---------------------------------------------------------

    @Test(timeout = 10_000)
    fun `movie resolves with search, startPlayVOD and getSlbInfo in order`() = runTest {
        val portal = FakePortal(
            script = mutableMapOf(
                "v3/searchByName" to { searchAnswer(searchItem("c-movie", "Coco")) },
                "v10/startPlayVOD" to { playAnswer("media-1", videoFormat = "ts") },
                "v14/getSlbInfo" to { slbAnswer() },
            ),
        )
        val result = client(portal).resolveDetailed("1234", "movie")

        assertTrue(result is MagisResult.Ok)
        val stream = (result as MagisResult.Ok).data
        // {base}/vod/{contentId}_media.{ts|mp4} with the reference's headers.
        assertEquals("https://cdn.example.com/vod/media-1_media.ts", stream.url)
        assertEquals("a=1&sign_type=cfl", stream.headers["Content-Auth"])
        assertEquals("lic-1", stream.headers["Content-License"])
        assertEquals("Ranger/4.9.4-17294ac0", stream.headers["User-Agent"])
        assertEquals("com.android.msandroid", stream.headers["App"])
        assertEquals("49902", stream.headers["App-Version"])

        val paths = portal.calls.map { it.path }
        // The mint (v3/snToken + v8/active) happens once, before the resolution calls.
        assertEquals(
            listOf("v3/snToken", "v8/active", "v3/searchByName", "v10/startPlayVOD", "v14/getSlbInfo"),
            paths,
        )
        val search = portal.calls.first { it.path == "v3/searchByName" }
        assertEquals("Coco", search.bean["value"])
        assertEquals(10, search.bean["pageSize"])
        val play = portal.calls.first { it.path == "v10/startPlayVOD" }
        assertEquals("c-movie", play.bean["contentId"])
        assertEquals("", play.bean["seriesContentId"])
    }

    @Test(timeout = 10_000)
    fun `series resolves the episode id through getItemData and passes seriesContentId`() = runTest {
        val portal = FakePortal(
            script = mutableMapOf(
                "v3/searchByName" to { searchAnswer(searchItem("c-series", "Coco", programType = "series")) },
                "v4/getItemData" to { itemDataAnswer(1 to "ep-1", 2 to "ep-2") },
                "v10/startPlayVOD" to { playAnswer("media-2", videoFormat = "mp4") },
                "v14/getSlbInfo" to { slbAnswer() },
            ),
        )
        val result = client(portal).resolveDetailed("1234", "tv", episode = 2)

        assertTrue(result is MagisResult.Ok)
        assertEquals("https://cdn.example.com/vod/media-2_media.mp4", (result as MagisResult.Ok).data.url)
        val paths = portal.calls.map { it.path }.filter { it.startsWith("v3/searchByName") || it in setOf("v4/getItemData", "v10/startPlayVOD", "v14/getSlbInfo") }
        assertEquals(
            listOf("v3/searchByName", "v4/getItemData", "v10/startPlayVOD", "v14/getSlbInfo"),
            paths,
        )
        val play = portal.calls.first { it.path == "v10/startPlayVOD" }
        assertEquals("ep-2", play.bean["contentId"])
        assertEquals("c-series", play.bean["seriesContentId"])
    }

    @Test(timeout = 10_000)
    fun `search falls back to the original title when the localized one has no candidate`() = runTest {
        var searchesSoFar = 0
        val portal = FakePortal(
            script = mutableMapOf(
                // First search (localized title) returns an unusable list; the second (original)
                // finds the candidate. The lookup returns BOTH titles distinct.
                "v3/searchByName" to {
                    searchesSoFar++
                    if (searchesSoFar == 1) {
                        searchAnswer(searchItem("", "Coco")) // no contentId → unusable
                    } else {
                        searchAnswer(searchItem("c-orig", "Coco (2017)"))
                    }
                },
                "v10/startPlayVOD" to { playAnswer("media-3", videoFormat = "mp4") },
                "v14/getSlbInfo" to { slbAnswer() },
            ),
        )
        val lookup = FakeTitleLookup(MagisTitleInfo(title = "Coco", originalTitle = "Coco Original"))
        val result = client(portal, lookup = lookup).resolveDetailed("1234", "movie")

        assertTrue(result is MagisResult.Ok)
        val searches = portal.calls.filter { it.path == "v3/searchByName" }
        assertEquals(2, searches.size)
        assertEquals("Coco", searches[0].bean["value"])
        assertEquals("Coco Original", searches[1].bean["value"])
    }

    // --- typed errors --------------------------------------------------------

    @Test(timeout = 10_000)
    fun `a failed lookup surfaces as a typed error, never a silent empty`() = runTest {
        val portal = FakePortal()
        val result = client(portal, lookup = FakeTitleLookup(info = null)).resolveDetailed("1234", "movie")
        assertTrue(result is MagisResult.PortalError)
        assertEquals(MagisVodClient.NO_TITLE, (result as MagisResult.PortalError).code)
        assertTrue("no hay llamadas al portal sin título", portal.calls.isEmpty())
    }

    @Test(timeout = 10_000)
    fun `all searches failing surfaces the last portal error`() = runTest {
        val portal = FakePortal(
            script = mutableMapOf(
                "v3/searchByName" to { MagisResult.PortalError("portal100001", "rechazado") },
            ),
        )
        val result = client(portal).resolveDetailed("1234", "movie")
        assertTrue(result is MagisResult.PortalError)
        assertEquals("portal100001", (result as MagisResult.PortalError).code)
    }

    @Test(timeout = 10_000)
    fun `no usable candidate after both titles surfaces a typed error`() = runTest {
        val portal = FakePortal(
            script = mutableMapOf("v3/searchByName" to { searchAnswer() }),
        )
        val result = client(portal, lookup = FakeTitleLookup(MagisTitleInfo("Coco", "Coco O"))).resolveDetailed("1234", "movie")
        assertTrue(result is MagisResult.PortalError)
        assertEquals(MagisVodClient.NO_CANDIDATE, (result as MagisResult.PortalError).code)
    }

    @Test(timeout = 10_000)
    fun `a missing episode surfaces a typed error`() = runTest {
        val portal = FakePortal(
            script = mutableMapOf(
                "v3/searchByName" to { searchAnswer(searchItem("c-series", "Coco", programType = "series")) },
                "v4/getItemData" to { itemDataAnswer(1 to "ep-1") },
            ),
        )
        val result = client(portal).resolveDetailed("1234", "series", episode = 9)
        assertTrue(result is MagisResult.PortalError)
        assertEquals(MagisVodClient.NO_EPISODE, (result as MagisResult.PortalError).code)
    }

    @Test(timeout = 10_000)
    fun `a play answer without media or license surfaces typed errors`() = runTest {
        val noMedia = FakePortal(
            script = mutableMapOf(
                "v3/searchByName" to { searchAnswer(searchItem("c-movie", "Coco")) },
                "v10/startPlayVOD" to { MagisResult.Ok(JSONObject().put("episodeList", JSONArray())) },
            ),
        )
        val r1 = client(noMedia).resolveDetailed("1234", "movie")
        assertEquals(MagisVodClient.NO_MEDIA, (r1 as MagisResult.PortalError).code)

        val noLicense = FakePortal(
            script = mutableMapOf(
                "v3/searchByName" to { searchAnswer(searchItem("c-movie", "Coco")) },
                "v10/startPlayVOD" to { playAnswer("m", videoFormat = "mp4", license = "") },
                "v14/getSlbInfo" to { slbAnswer() },
            ),
        )
        val r2 = client(noLicense).resolveDetailed("1234", "movie")
        assertEquals(MagisVodClient.NO_LICENSE, (r2 as MagisResult.PortalError).code)
    }

    @Test(timeout = 10_000)
    fun `an slb without a vod cfl entry surfaces a typed error`() = runTest {
        val portal = FakePortal(
            script = mutableMapOf(
                "v3/searchByName" to { searchAnswer(searchItem("c-movie", "Coco")) },
                "v10/startPlayVOD" to { playAnswer("m", videoFormat = "mp4") },
                "v14/getSlbInfo" to { MagisResult.Ok(JSONObject()) },
            ),
        )
        val result = client(portal).resolveDetailed("1234", "movie")
        assertTrue(result is MagisResult.PortalError)
        assertEquals(MagisVodClient.NO_CDN, (result as MagisResult.PortalError).code)
    }

    // --- SLB cache reuse -----------------------------------------------------

    @Test(timeout = 10_000)
    fun `a second resolve within the TTL skips getSlbInfo`() = runTest {
        val portal = FakePortal(
            script = mutableMapOf(
                "v3/searchByName" to { searchAnswer(searchItem("c-movie", "Coco")) },
                "v10/startPlayVOD" to { playAnswer("m", videoFormat = "mp4") },
                "v14/getSlbInfo" to { slbAnswer() },
            ),
        )
        val c = client(portal)
        assertNotNull(c.resolveDetailed("1234", "movie"))
        assertNotNull(c.resolveDetailed("1234", "movie"))

        val slbCalls = portal.calls.count { it.path == "v14/getSlbInfo" }
        assertEquals("el slb se reusa dentro del TTL", 1, slbCalls)
    }

    @Test(timeout = 10_000)
    fun `an expired slb answer triggers a fresh getSlbInfo`() = runTest {
        var now = 1_000_000L
        val portal = FakePortal(
            script = mutableMapOf(
                "v3/searchByName" to { searchAnswer(searchItem("c-movie", "Coco")) },
                "v10/startPlayVOD" to { playAnswer("m", videoFormat = "mp4") },
                "v14/getSlbInfo" to { slbAnswer() },
            ),
        )
        val session = MagisSession(portal, FakeStore())
        val c = MagisVodClient(
            portal = portal,
            session = session,
            configProvider = { config },
            titleLookup = FakeTitleLookup(),
            nowMs = { now },
        )
        assertNotNull(c.resolveDetailed("1234", "movie"))
        // expiresAt = 1_000_000 + 14_400_000 - 300_000 = 15_100_000.
        now = 15_100_000L
        assertNotNull(c.resolveDetailed("1234", "movie"))
        assertEquals("caducada → segunda llamada", 2, portal.calls.count { it.path == "v14/getSlbInfo" })
    }

    @Test(timeout = 10_000)
    fun `a new session token does not reuse the previous owner's slb`() = runTest {
        val store = FakeStore()
        val portal = FakePortal(
            script = mutableMapOf(
                "v3/searchByName" to { searchAnswer(searchItem("c-movie", "Coco")) },
                "v10/startPlayVOD" to { playAnswer("m", videoFormat = "mp4") },
                "v14/getSlbInfo" to { slbAnswer() },
            ),
        )
        val c = client(portal, store = store)
        assertNotNull(c.resolveDetailed("1234", "movie"))

        // The session's token changes under the client (a remint, a kick, anything).
        val previous = store.read()!!
        store.save(previous.copy(userId = "u2", userToken = "t2"))

        assertNotNull(c.resolveDetailed("1234", "movie"))
        assertEquals("otro dueño de token no reusa", 2, portal.calls.count { it.path == "v14/getSlbInfo" })
    }

    @Test(timeout = 10_000)
    fun `resolve returns null on failure and the stream on success`() = runTest {
        val failing = FakePortal(script = mutableMapOf("v3/searchByName" to { MagisResult.PortalError("x", "y") }))
        assertNull(client(failing).resolve("1234", "movie"))

        val ok = FakePortal(
            script = mutableMapOf(
                "v3/searchByName" to { searchAnswer(searchItem("c-movie", "Coco")) },
                "v10/startPlayVOD" to { playAnswer("m", videoFormat = "mp4") },
                "v14/getSlbInfo" to { slbAnswer() },
            ),
        )
        assertNotNull(client(ok).resolve("1234", "movie"))
    }
}
