package com.nuvio.tv.ext.livetv.data.epg

import java.io.File
import java.security.MessageDigest

/**
 * Keeps the **raw** response of each source on disk, with a TTL.
 *
 * Raw rather than parsed or inflated: a country feed can inflate to a hundred megabytes, and the
 * reference fork wrote exactly that to disk per source. Keeping the compressed bytes keeps the cache
 * small, and the parser inflates and caps on every read anyway.
 *
 * Writes go through a temporary file and a rename, because a half-written cache file would be handed
 * to the parser and reported as a malformed document -- an error with nothing to do with the real
 * cause. A rename within one filesystem is atomic.
 */
class EpgDiskCache(
    private val root: File,
    private val ttlMs: Long = DEFAULT_TTL_MS
) {

    fun read(sourceId: String, nowEpochMs: Long): ByteArray? {
        val file = fileFor(sourceId)
        if (!file.isFile) return null
        if (nowEpochMs - file.lastModified() > ttlMs) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    fun write(sourceId: String, bytes: ByteArray, nowEpochMs: Long) {
        root.mkdirs()
        val target = fileFor(sourceId)
        val temporary = File(root, "${target.name}.tmp")
        runCatching {
            temporary.writeBytes(bytes)
            // Stamp the temporary file with the caller's clock. The TTL check reads this timestamp,
            // and letting the write use the filesystem's clock while the check uses an injected one
            // means the two disagree about what time it is. In production they are the same wall
            // clock anyway; in a test the injected one wins cleanly instead of silently.
            temporary.setLastModified(nowEpochMs)
            if (!temporary.renameTo(target)) {
                // Some filesystems refuse a rename onto an existing file.
                target.delete()
                if (!temporary.renameTo(target)) temporary.delete()
            }
        }.onFailure { temporary.delete() }
    }

    fun clear() {
        root.listFiles()?.forEach { it.delete() }
    }

    /** Hashed so a source id containing slashes or a token never becomes a path. */
    private fun fileFor(sourceId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sourceId.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
            .take(24)
        return File(root, "epg_$digest.xml")
    }

    companion object {
        /** A day: guides change slowly, and the sources are large. */
        const val DEFAULT_TTL_MS: Long = 24L * 60 * 60 * 1000
    }
}
