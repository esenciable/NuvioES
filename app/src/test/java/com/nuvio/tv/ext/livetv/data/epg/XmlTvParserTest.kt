package com.nuvio.tv.ext.livetv.data.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream

/**
 * The document is assembled by concatenation and never through `trimIndent()`. Interpolating the
 * multi-line doctype into an indented raw string defeats the common-indent calculation -- the line
 * carrying `<!DOCTYPE` has no indentation, so nothing gets stripped and the XML declaration ends up
 * indented, which is a hard parse error ("processing instruction target matching [xX][mM][lL] is not
 * allowed"). That cost a whole test run to diagnose; the fixture no longer allows it.
 */
class XmlTvParserTest {

    private val reference: Long =
        OffsetDateTime.parse("2026-09-29T12:00:00+00:00").toInstant().toEpochMilli()

    private val xmltvDate: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")

    /** The header every real XMLTV feed carries, including the external DTD reference. */
    private val doctype = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<!DOCTYPE tv SYSTEM \"xmltv.dtd\">"

    private fun at(offsetMs: Long): String = OffsetDateTime
        .ofInstant(Instant.ofEpochMilli(reference + offsetMs), ZoneOffset.UTC)
        .format(xmltvDate)

    private fun document(body: String): String = "$doctype\n<tv>\n$body</tv>\n"

    private fun parse(
        xml: String,
        maxBytes: Long = XmlTvParser.DEFAULT_MAX_DECOMPRESSED_BYTES,
        maxPrograms: Int = XmlTvParser.DEFAULT_MAX_PROGRAMS
    ): XmlTvGuide = XmlTvParser.parse(
        source = xml.toByteArray(Charsets.UTF_8).inputStream(),
        referenceEpochMs = reference,
        maxDecompressedBytes = maxBytes,
        maxPrograms = maxPrograms
    )

    private fun channel(id: String, name: String, icon: String? = null): String = buildString {
        append("  <channel id=\"$id\">\n")
        append("    <display-name lang=\"es\">$name</display-name>\n")
        if (icon != null) append("    <icon src=\"$icon\" />\n")
        append("  </channel>\n")
    }

    private fun programme(
        channelId: String,
        fromOffsetMs: Long,
        toOffsetMs: Long,
        title: String,
        description: String? = null
    ): String = buildString {
        append("  <programme start=\"${at(fromOffsetMs)}\" stop=\"${at(toOffsetMs)}\" channel=\"$channelId\">\n")
        append("    <title lang=\"es\">$title</title>\n")
        if (description != null) append("    <desc lang=\"es\">$description</desc>\n")
        append("  </programme>\n")
    }

    @Test
    fun `parses channels and programmes`() {
        val xml = document(
            channel("c1", "Canal 13 de Argentina (El Trece)", "https://example.test/13.png") +
                programme("c1", -HOUR, 0L, "Telenoche", "Noticiero central") +
                programme("c1", 0L, HOUR, "Los Simpsons")
        )

        val guide = parse(xml)

        assertEquals(1, guide.channels.size)
        assertEquals("Canal 13 de Argentina (El Trece)", guide.channels.single().displayNames.first())
        assertEquals("https://example.test/13.png", guide.channels.single().iconUrl)
        assertEquals(2, guide.totalProgramsParsed)

        val programs = guide.programsByChannelId.getValue("c1")
        assertEquals("Telenoche", programs[0].title)
        assertEquals("Noticiero central", programs[0].description)
        assertEquals("Los Simpsons", programs[1].title)
        assertNull(programs[1].description)
        assertTrue(programs[0].isOnAirAt(reference - 1))
        assertFalse(programs[0].isOnAirAt(reference))
    }

    @Test
    fun `decodes xml entities and accents`() {
        val xml = document(
            channel("c1", "A&amp;B Network") +
                programme("c1", 0L, HOUR, "Caf&#233; &amp; T&#233;")
        )

        val guide = parse(xml)

        assertEquals("A&B Network", guide.channels.single().displayNames.first())
        assertEquals("Café & Té", guide.programsByChannelId.getValue("c1").single().title)
    }

    @Test
    fun `accepts the doctype that real feeds carry`() {
        // Regression guard: the usual hardening advice (`disallow-doctype-decl`) would reject every
        // legitimate XMLTV document, since real feeds all start with this header.
        val xml = document(channel("c1", "Uno") + programme("c1", 0L, HOUR, "Programa"))

        assertEquals(1, parse(xml).totalProgramsParsed)
    }

    @Test
    fun `never reads an external dtd`() {
        val dtd = File.createTempFile("xmltv", ".dtd").apply {
            writeText("<!ENTITY xxe \"EXPANDED-FROM-DISK\">")
            deleteOnExit()
        }
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE tv SYSTEM \"${dtd.toURI()}\">\n" +
            "<tv><channel id=\"c1\"><display-name>&xxe;</display-name></channel></tv>\n"

        // The externally declared entity must not reach the document. Verified against a real DTD on
        // disk: the parser refuses to load it, so the entity stays undefined and expands to nothing.
        val outcome = runCatching { parse(xml) }
        val names = outcome.getOrNull()?.channels?.single()?.displayNames.orEmpty()
        assertFalse("the external DTD was read: $names", names.any { it.contains("EXPANDED-FROM-DISK") })
        assertFalse(
            "the external DTD was read: ${outcome.exceptionOrNull()?.message}",
            outcome.exceptionOrNull()?.message.orEmpty().contains("EXPANDED-FROM-DISK")
        )
    }

    @Test
    fun `fails instead of truncating when the programme limit is exceeded`() {
        val body = channel("c1", "Uno") + (0 until 10).joinToString("") { index ->
            programme("c1", index * HOUR, (index + 1) * HOUR, "Programa $index")
        }

        assertThrows(EpgLimitExceededException::class.java) { parse(document(body), maxPrograms = 5) }
    }

    @Test
    fun `fails when the decompressed size limit is exceeded`() {
        // The shape of a decompression bomb: a modest document refused once inflated past the cap.
        // A truncated guide would be indistinguishable from a complete one.
        val xml = document(channel("c1", "Uno") + programme("c1", 0L, HOUR, "Programa"))

        val failure = assertThrows(EpgLimitExceededException::class.java) { parse(xml, maxBytes = 64) }
        assertTrue(failure.message.orEmpty().contains("byte limit"))
    }

    @Test
    fun `inflates a gzipped document transparently`() {
        val xml = document(channel("c1", "Uno") + programme("c1", 0L, HOUR, "Programa"))
        val gzipped = gzip(xml)

        assertEquals(0x1F, gzipped[0].toInt() and 0xFF)
        assertEquals(0x8B, gzipped[1].toInt() and 0xFF)

        assertEquals(1, XmlTvParser.parse(gzipped.inputStream(), reference).totalProgramsParsed)
    }

    @Test
    fun `caps the decompressed side of a gzip, not the compressed side`() {
        val body = channel("c1", "Uno") + (0 until 4000).joinToString("") { index ->
            programme("c1", index * HOUR, (index + 1) * HOUR, "Programa numero $index")
        }
        val xml = document(body)
        val gzipped = gzip(xml)

        // The compressed payload is tiny; the inflated one is not. Capping the wrong side of the
        // gzip would let this through, which is exactly the bomb the cap exists to stop.
        assertTrue("fixture should compress well", gzipped.size < xml.length / 10)

        assertThrows(EpgLimitExceededException::class.java) {
            XmlTvParser.parse(gzipped.inputStream(), reference, maxDecompressedBytes = 4096)
        }
    }

    @Test
    fun `skips programmes outside the window and reports how many`() {
        val xml = document(
            channel("c1", "Uno") +
                programme("c1", -200 * HOUR, -199 * HOUR, "Viejo") +
                programme("c1", 200 * HOUR, 201 * HOUR, "Lejano") +
                programme("c1", 0L, HOUR, "Ahora")
        )

        val guide = parse(xml)

        assertEquals(1, guide.totalProgramsParsed)
        assertEquals(2, guide.programsSkippedOutOfWindow)
        assertEquals("Ahora", guide.programsByChannelId.getValue("c1").single().title)
    }

    @Test
    fun `keeps the first icon when a feed repeats it`() {
        val xml = document(
            "  <channel id=\"c1\">\n" +
                "    <display-name lang=\"es\">Uno</display-name>\n" +
                "    <icon src=\"https://example.test/first.png\" />\n" +
                "    <icon src=\"https://example.test/first.png\" />\n" +
                "    <icon src=\"https://example.test/second.png\" />\n" +
                "  </channel>\n"
        )

        assertEquals("https://example.test/first.png", parse(xml).channels.single().iconUrl)
    }

    @Test
    fun `drops a programme whose dates cannot be read`() {
        val xml = document(
            channel("c1", "Uno") +
                "  <programme start=\"no-es-fecha\" stop=\"${at(HOUR)}\" channel=\"c1\"><title>Malo</title></programme>\n" +
                programme("c1", 0L, HOUR, "Bueno")
        )

        val guide = parse(xml)

        assertEquals(1, guide.totalProgramsParsed)
        assertEquals("Bueno", guide.programsByChannelId.getValue("c1").single().title)
    }

    @Test
    fun `reads a timestamp without an offset as utc`() {
        val xml = document(
            channel("c1", "Uno") +
                "  <programme start=\"20260929120000\" stop=\"20260929130000\" channel=\"c1\"><title>Sin offset</title></programme>\n"
        )

        val guide = parse(xml)

        assertEquals(1, guide.totalProgramsParsed)
        assertEquals(reference, guide.programsByChannelId.getValue("c1").single().startEpochMs)
    }

    @Test
    fun `sorts programmes by start`() {
        val xml = document(
            channel("c1", "Uno") +
                programme("c1", 2 * HOUR, 3 * HOUR, "Tercero") +
                programme("c1", 0L, HOUR, "Primero") +
                programme("c1", HOUR, 2 * HOUR, "Segundo")
        )

        val titles = parse(xml).programsByChannelId.getValue("c1").map { it.title }
        assertEquals(listOf("Primero", "Segundo", "Tercero"), titles)
    }

    @Test
    fun `fails on malformed xml instead of returning a partial guide`() {
        val xml = doctype + "\n<tv>" + channel("c1", "Uno") +
            "<programme start=\"${at(0)}\" stop=\"${at(HOUR)}\" channel=\"c1\"><title>Sin cerrar</tv>"

        assertThrows(Exception::class.java) { parse(xml) }
    }

    @Test
    fun `keeps several channels apart`() {
        val xml = document(
            channel("c1", "Uno") +
                channel("c2", "Dos") +
                programme("c1", 0L, HOUR, "De uno") +
                programme("c2", 0L, HOUR, "De dos")
        )

        val guide = parse(xml)

        assertEquals(setOf("c1", "c2"), guide.programsByChannelId.keys)
        assertEquals("De uno", guide.programsByChannelId.getValue("c1").single().title)
        assertEquals("De dos", guide.programsByChannelId.getValue("c2").single().title)
    }

    private fun gzip(xml: String): ByteArray = ByteArrayOutputStream()
        .also { out -> GZIPOutputStream(out).use { it.write(xml.toByteArray(Charsets.UTF_8)) } }
        .toByteArray()

    private companion object {
        const val HOUR = 60L * 60L * 1000L
    }
}
