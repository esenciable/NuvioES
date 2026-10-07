package com.nuvio.tv.ext.livetv.data.premiumtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The parser is pure: text in, entries out. The fixtures mix hand-made edge cases with REAL lines
 * copied from the PremiumTV list (verified 2026-10-07), because the whole point is to parse THAT
 * file — not an idealized M3U dialect.
 */
class M3uParserTest {

    // Real line pair from the PremiumTV list: attributes quoted, per-entry headers, URL on the
    // fourth line after two EXTVLCOPT directives.
    private val tcsEntry = """
        #EXTINF:-1 tvg-logo="https://i.ibb.co/ccx6tKDg/Canal-2-El-Salvador-2005.png" group-title="El Salvador - TCS",Canal 2 TCS SD
        #EXTVLCOPT:http-user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36 Edg/145.0.0.0
        #EXTVLCOPT:http-referrer=https://teleon.tv/
        https://signal.teleon.live/play/0ovixKuZ.m3u8?token=test
    """.trimIndent()

    // Real entry from the PremiumTV list: a Samsung TV Plus channel served through jmp2.uk that
    // declares NO headers at all.
    private val samsungEntry = """
        #EXTINF:-1 tvg-logo="https://tvpnlogopeu.samsungcloud.tv/platform/image/sourcelogo/vc/00/02/34/ES300029LP_20250326T013525SQUARE.png" group-title="Anime",Anime Visión (España)[Opc.1]
        https://jmp2.uk/stvp-ES300029LP
    """.trimIndent()

    @Test
    fun `parses a real entry with quoted attributes and per-entry headers`() {
        val entries = M3uParser.parse(tcsEntry)

        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("Canal 2 TCS SD", entry.name)
        assertEquals("https://i.ibb.co/ccx6tKDg/Canal-2-El-Salvador-2005.png", entry.logoUrl)
        assertEquals("El Salvador - TCS", entry.groupTitle)
        assertEquals("https://signal.teleon.live/play/0ovixKuZ.m3u8?token=test", entry.url)
        // The per-entry headers the reference plugin ignored: they belong to THIS entry only.
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/145.0.0.0 Safari/537.36 Edg/145.0.0.0",
            entry.userAgent,
        )
        assertEquals("https://teleon.tv/", entry.referrer)
    }

    @Test
    fun `parses a real entry with no headers at all`() {
        val entries = M3uParser.parse(samsungEntry)

        val entry = entries.single()
        assertEquals("Anime Visión (España)[Opc.1]", entry.name)
        assertEquals("Anime", entry.groupTitle)
        assertEquals("https://jmp2.uk/stvp-ES300029LP", entry.url)
        assertNull(entry.userAgent)
        assertNull(entry.referrer)
    }

    @Test
    fun `groups by group-title and falls back to a synthetic group when it is missing`() {
        val entries = M3uParser.parse(
            """
            #EXTINF:-1 group-title="Deportes",Con título
            http://stream/1.m3u8
            #EXTINF:-1 group-title="Deportes",Otro del grupo
            http://stream/2.m3u8
            #EXTINF:-1 tvg-logo="http://l.png",Sin grupo
            http://stream/3.m3u8
            """.trimIndent(),
        )

        assertEquals(
            mapOf(
                "Deportes" to listOf("Con título", "Otro del grupo"),
                M3uParser.FALLBACK_GROUP to listOf("Sin grupo"),
            ),
            entries.groupBy({ it.groupTitle }, { it.name }).mapValues { it.value },
        )
    }

    @Test
    fun `skips blank lines, comments and the EXTINF3U header`() {
        val entries = M3uParser.parse(
            """
            #EXTM3U

            # Just a comment
            #EXTINF:-1 group-title="A",Uno
            http://stream/1.m3u8

            #EXTVLCOPT:http-user-agent=not-a-header-either
            """.trimIndent(),
        )

        assertEquals(1, entries.size)
        assertEquals("Uno", entries.single().name)
        // The trailing EXTVLCOPT has no pending entry: it is an orphan, not a header of "Uno".
        assertNull(entries.single().userAgent)
    }

    @Test
    fun `drops an entry whose EXTINF is never followed by a URL`() {
        val entries = M3uParser.parse(
            """
            #EXTINF:-1 group-title="A",Sin URL
            #EXTINF:-1 group-title="A",Con URL
            http://stream/2.m3u8
            #EXTINF:-1 group-title="A",Colgando al final
            """.trimIndent(),
        )

        assertEquals(listOf("Con URL"), entries.map { it.name })
    }

    @Test
    fun `ignores orphan EXTVLCOPT lines before any EXTINF`() {
        val entries = M3uParser.parse(
            """
            #EXTVLCOPT:http-user-agent=orphan
            #EXTVLCOPT:http-referrer=https://orphan.example/
            #EXTINF:-1 group-title="A",Primero
            http://stream/1.m3u8
            """.trimIndent(),
        )

        assertEquals(1, entries.size)
        assertNull(entries.single().userAgent)
        assertNull(entries.single().referrer)
    }

    @Test
    fun `keeps a title that contains commas`() {
        val entries = M3uParser.parse(
            """
            #EXTINF:-1 group-title="A",Nombre, con coma, y más
            http://stream/1.m3u8
            """.trimIndent(),
        )

        assertEquals("Nombre, con coma, y más", entries.single().name)
    }

    @Test
    fun `handles unquoted attribute values`() {
        val entries = M3uParser.parse(
            """
            #EXTINF:-1 tvg-logo=http://logo.example/a.png group-title=Noticias,Canal
            http://stream/1.m3u8
            """.trimIndent(),
        )

        val entry = entries.single()
        assertEquals("http://logo.example/a.png", entry.logoUrl)
        assertEquals("Noticias", entry.groupTitle)
    }

    @Test
    fun `keeps duplicate names as separate entries`() {
        val entries = M3uParser.parse(
            """
            #EXTINF:-1 group-title="A",Canal 2 TCS SD
            http://stream/1.m3u8
            #EXTINF:-1 group-title="A",Canal 2 TCS SD
            http://stream/2.m3u8
            """.trimIndent(),
        )

        assertEquals(2, entries.size)
        assertEquals(listOf("http://stream/1.m3u8", "http://stream/2.m3u8"), entries.map { it.url })
    }

    @Test
    fun `an empty or comment-only document parses to nothing`() {
        assertEquals(emptyList<M3uEntry>(), M3uParser.parse(""))
        assertEquals(emptyList<M3uEntry>(), M3uParser.parse("#EXTM3U\n\n#nada\n"))
    }
}
