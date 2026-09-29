package com.nuvio.tv.ext.livetv.data.epg

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Raised when an XMLTV source exceeds one of the hard bounds this feature enforces.
 *
 * It is deliberately a failure and not a silent truncation. A truncated guide is
 * indistinguishable from a complete one, so the user would see a guide that is quietly missing
 * programming instead of an error they can act on. Failing lets the caller keep the guide it
 * already had (PRD RF-37).
 */
class EpgLimitExceededException(message: String) : Exception(message)

/**
 * An [InputStream] that fails once more than [maxBytes] have been consumed.
 *
 * This is the gzip-bomb defence, so it must wrap the **decompressed** side: a 2 MB gzip can expand
 * to gigabytes, and the reference fork read the whole thing into a single `String` with no bound at
 * all, which OOMs a television with 1-2 GB of RAM.
 */
internal class CappedInputStream(
    input: InputStream,
    private val maxBytes: Long,
    private val label: String
) : FilterInputStream(input) {

    private var consumed = 0L

    override fun read(): Int {
        val byte = super.read()
        if (byte >= 0) account(1L)
        return byte
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = super.read(b, off, len)
        if (read > 0) account(read.toLong())
        return read
    }

    private fun account(bytes: Long) {
        consumed += bytes
        if (consumed > maxBytes) {
            throw EpgLimitExceededException(
                "$label exceeded the $maxBytes byte limit (read $consumed and still not finished)"
            )
        }
    }
}
