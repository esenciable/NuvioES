package com.nuvio.tv.ext.livetv.data.epg

import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.XMLReader
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.PushbackInputStream
import java.io.StringReader
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Parses XMLTV into [XmlTvGuide], with every bound that untrusted remote XML needs.
 *
 * ### Why SAX and not the alternatives
 *
 * The reference fork hand-rolled a string scanner over the whole document. That is fragile with
 * entities (`&amp;`, `&#233;`, quoted attributes) and, worse, it read the decompressed document into
 * a single `String` with no cap. SAX streams, and it decodes entities for free. It also runs on plain
 * JVM unit tests, unlike `android.util.Xml`, so this is testable without a device.
 *
 * ### The DOCTYPE trap
 *
 * Real XMLTV documents start with `<!DOCTYPE tv SYSTEM "xmltv.dtd">` -- verified against the
 * production source `epgshare01.online`. So the usual hardening advice of setting
 * `disallow-doctype-decl` would reject **every legitimate file**. It is deliberately NOT set.
 *
 * The risk in that header is different and narrower: `SYSTEM "xmltv.dtd"` invites the parser to
 * **fetch** `xmltv.dtd`. That is refused by installing an [EntityResolver] that answers every
 * external reference with empty content, plus best-effort attempts to disable external general and
 * parameter entities and external DTD loading. Each feature is attempted independently and a
 * rejection is not fatal, because Android's parser does not support every feature name -- the
 * resolver is the backstop that works everywhere.
 *
 * ### Failure, not truncation
 *
 * Exceeding a bound raises [EpgLimitExceededException]. A truncated guide is indistinguishable from a
 * complete one, so silently cutting it off would show the user a guide quietly missing programming.
 */
object XmlTvParser {

    /** Decompressed bytes. Generous for a real country feed, fatal for a decompression bomb. */
    const val DEFAULT_MAX_DECOMPRESSED_BYTES: Long = 96L * 1024 * 1024

    /** Programmes accepted per document, after window filtering. */
    const val DEFAULT_MAX_PROGRAMS: Int = 250_000

    /** How far back from "now" the guide is kept. */
    const val DEFAULT_WINDOW_PAST_MS: Long = 48L * 60 * 60 * 1000

    /** How far forward from "now" the guide is kept. */
    const val DEFAULT_WINDOW_FUTURE_MS: Long = 96L * 60 * 60 * 1000

    private val GZIP_MAGIC = byteArrayOf(0x1F, 0x8B.toByte())

    private val XMLTV_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")

    fun parse(
        source: InputStream,
        referenceEpochMs: Long,
        maxDecompressedBytes: Long = DEFAULT_MAX_DECOMPRESSED_BYTES,
        maxPrograms: Int = DEFAULT_MAX_PROGRAMS,
        windowPastMs: Long = DEFAULT_WINDOW_PAST_MS,
        windowFutureMs: Long = DEFAULT_WINDOW_FUTURE_MS
    ): XmlTvGuide {
        val handler = GuideHandler(
            windowStartMs = referenceEpochMs - windowPastMs,
            windowEndMs = referenceEpochMs + windowFutureMs,
            maxPrograms = maxPrograms
        )
        val reader = hardenedReader()
        reader.contentHandler = handler
        reader.entityResolver = NoExternalEntities
        reader.errorHandler = handler
        try {
            reader.parse(InputSource(boundedStream(source, maxDecompressedBytes)))
        } catch (failure: SAXException) {
            // SAX wraps whatever a handler throws, so our own limit signal would arrive as a
            // SAXException and callers could not tell "this source is too big" from "this document
            // is malformed". Unwrap it so the distinction survives.
            throw failure.limitCause() ?: failure
        }
        return handler.toGuide()
    }

    private fun SAXException.limitCause(): EpgLimitExceededException? =
        generateSequence<Throwable>(this) { it.cause }
            .filterIsInstance<EpgLimitExceededException>()
            .firstOrNull()

    /**
     * Sniffs the gzip magic bytes and, if present, wraps the stream. Sniffing beats trusting a URL
     * suffix or a `Content-Encoding` header: OkHttp transparently decompresses some responses and not
     * others, so by the time the body reaches here it may or may not already be inflated.
     *
     * The byte cap wraps the **decompressed** side, which is the one a bomb attacks.
     */
    private fun boundedStream(source: InputStream, maxDecompressedBytes: Long): InputStream {
        val pushback = PushbackInputStream(source, GZIP_MAGIC.size)
        val head = ByteArray(GZIP_MAGIC.size)
        var filled = 0
        while (filled < head.size) {
            val read = pushback.read(head, filled, head.size - filled)
            if (read < 0) break
            filled += read
        }
        if (filled > 0) pushback.unread(head, 0, filled)

        val body = if (filled == GZIP_MAGIC.size && head.contentEquals(GZIP_MAGIC)) {
            GZIPInputStream(pushback)
        } else {
            pushback
        }
        return CappedInputStream(body, maxDecompressedBytes, "XMLTV source")
    }

    private fun hardenedReader(): XMLReader {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        // Never `disallow-doctype-decl`: real XMLTV carries one. See the class doc.
        attempt(factory, "http://xml.org/sax/features/external-general-entities", false)
        attempt(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        attempt(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        return factory.newSAXParser().xmlReader
    }

    private fun attempt(factory: SAXParserFactory, feature: String, value: Boolean) {
        try {
            factory.setFeature(feature, value)
        } catch (_: Exception) {
            // Not honoured here. The entity resolver still refuses every external reference, so the
            // protection does not depend on the feature being supported.
        }
    }

    /**
     * Answers every external reference with empty content. This is what stops `SYSTEM "xmltv.dtd"`
     * from becoming a network fetch -- both an SSRF-shaped hole and a hard dependency on a
     * third-party host being reachable.
     */
    private object NoExternalEntities : EntityResolver {
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
            InputSource(StringReader(""))
    }

    private class GuideHandler(
        private val windowStartMs: Long,
        private val windowEndMs: Long,
        private val maxPrograms: Int
    ) : DefaultHandler() {

        private val channelsById = LinkedHashMap<String, EpgChannel>()
        private val programsByChannelId = LinkedHashMap<String, MutableList<EpgProgram>>()
        private val displayNameBuffer = StringBuilder()

        private var acceptedPrograms = 0
        private var skippedOutOfWindow = 0

        private var currentChannelId: String? = null
        private var currentChannelNames = mutableListOf<String>()
        private var currentChannelIcon: String? = null

        private var programChannelId: String? = null
        private var programStartMs = -1L
        private var programStopMs = -1L
        private var programTitle: StringBuilder? = null
        private var programDescription: StringBuilder? = null

        private var readingDisplayName = false
        private var readingTitle = false
        private var readingDescription = false

        // DefaultHandler swallows fatal errors, which would turn a malformed document into a
        // partial guide that looks complete. Fail instead: the caller keeps the guide it had.
        override fun fatalError(exception: SAXParseException) {
            throw exception
        }

        override fun error(exception: SAXParseException) {
            throw exception
        }

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            when (nameOf(localName, qName)) {
                "channel" -> {
                    currentChannelId = attributes.getValue("id")
                    currentChannelNames = mutableListOf()
                    currentChannelIcon = null
                }

                "display-name" -> if (currentChannelId != null) {
                    readingDisplayName = true
                }

                "icon" -> if (currentChannelId != null && currentChannelIcon == null) {
                    // Real feeds repeat the same <icon> several times; the first one wins.
                    currentChannelIcon = attributes.getValue("src")
                }

                "programme" -> {
                    programChannelId = attributes.getValue("channel")
                    programStartMs = parseXmlTvDate(attributes.getValue("start")) ?: -1L
                    programStopMs = parseXmlTvDate(attributes.getValue("stop")) ?: -1L
                    programTitle = null
                    programDescription = null
                }

                "title" -> if (programChannelId != null) readingTitle = true
                "desc" -> if (programChannelId != null) readingDescription = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            when {
                readingDisplayName -> displayNameBuffer.appendRange(ch, start, start + length)
                readingTitle -> bufferOfTitle().appendRange(ch, start, start + length)
                readingDescription -> bufferOfDescription().appendRange(ch, start, start + length)
            }
        }

        private fun bufferOfTitle(): StringBuilder =
            programTitle ?: StringBuilder().also { programTitle = it }

        private fun bufferOfDescription(): StringBuilder =
            programDescription ?: StringBuilder().also { programDescription = it }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (nameOf(localName, qName)) {
                "display-name" -> {
                    if (readingDisplayName) {
                        readingDisplayName = false
                        val text = displayNameBuffer.toString().trim()
                        displayNameBuffer.setLength(0)
                        if (text.isNotEmpty()) currentChannelNames.add(text)
                    }
                }

                "channel" -> {
                    val id = currentChannelId
                    if (!id.isNullOrBlank()) {
                        channelsById[id] = EpgChannel(
                            id = id,
                            displayNames = currentChannelNames.toList(),
                            iconUrl = currentChannelIcon
                        )
                    }
                    currentChannelId = null
                    currentChannelNames = mutableListOf()
                    currentChannelIcon = null
                }

                "title" -> readingTitle = false
                "desc" -> readingDescription = false

                "programme" -> {
                    readingTitle = false
                    readingDescription = false
                    acceptProgram()
                }
            }
        }

        fun toGuide(): XmlTvGuide = XmlTvGuide(
            channels = channelsById.values.toList(),
            programsByChannelId = programsByChannelId.mapValues { (_, programs) ->
                programs.sortedBy(EpgProgram::startEpochMs)
            },
            totalProgramsParsed = acceptedPrograms,
            programsSkippedOutOfWindow = skippedOutOfWindow
        )

        private fun acceptProgram() {
            val channelId = programChannelId
            programChannelId = null

            if (channelId.isNullOrBlank() || programStartMs < 0L || programStopMs <= programStartMs) {
                return
            }
            if (programStartMs >= windowEndMs || programStopMs <= windowStartMs) {
                skippedOutOfWindow++
                return
            }
            if (acceptedPrograms >= maxPrograms) {
                throw EpgLimitExceededException("XMLTV source exceeded the $maxPrograms programme limit")
            }
            val title = programTitle?.toString()?.trim().orEmpty()
            if (title.isEmpty()) return

            programsByChannelId.getOrPut(channelId) { mutableListOf() }.add(
                EpgProgram(
                    channelId = channelId,
                    title = title,
                    description = programDescription?.toString()?.trim()?.takeIf(String::isNotEmpty),
                    startEpochMs = programStartMs,
                    stopEpochMs = programStopMs
                )
            )
            acceptedPrograms++
        }

        private fun nameOf(localName: String?, qName: String?): String =
            (if (qName.isNullOrBlank()) localName.orEmpty() else qName).lowercase()

        /**
         * `YYYYMMDDHHMMSS +ZZZZ` is the documented form, but not every producer emits the offset. A
         * bare 14-digit timestamp is read as UTC rather than dropped.
         */
        private fun parseXmlTvDate(raw: String?): Long? {
            val text = raw?.trim()?.replace(WHITESPACE, " ") ?: return null
            if (text.isEmpty()) return null
            return try {
                OffsetDateTime.parse(text, XMLTV_DATE).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                try {
                    OffsetDateTime.parse("$text +0000", XMLTV_DATE).toInstant().toEpochMilli()
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }

        private companion object {
            val WHITESPACE = Regex("\\s+")
        }
    }
}
