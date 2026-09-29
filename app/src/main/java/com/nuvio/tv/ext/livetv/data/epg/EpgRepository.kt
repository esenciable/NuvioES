package com.nuvio.tv.ext.livetv.data.epg

import com.nuvio.tv.ext.livetv.domain.model.EpgSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why a source did not contribute. A code, not a sentence: the repository has no `Context` and
 * nothing user-visible may be composed in Kotlin (RF-53). The UI turns this into a localised string. */
enum class EpgFailureReason {
    DOWNLOAD_FAILED,
    TOO_LARGE,
    MALFORMED,
    NOTHING_PARSED
}

data class EpgFailure(
    val sourceName: String,
    val reason: EpgFailureReason
)

/** Reads the raw bytes of an XMLTV document. Null means the source could not be read. */
fun interface EpgDocumentFetcher {
    suspend fun fetch(url: String): ByteArray?
}

/**
 * A fully built guide. Immutable, so publishing one is a single reference swap rather than a series
 * of mutations observers can catch halfway through.
 *
 * The reference fork kept four mutable maps, cleared and refilled them in place, and guarded only the
 * writes -- so a reader on another thread could observe a `HashMap` while it was being cleared.
 */
data class EpgSnapshot(
    val guide: XmlTvGuide,
    val index: EpgGuideIndex,
    val loadedAtEpochMs: Long,
    val sourceIds: List<String>
) {
    val isEmpty: Boolean get() = guide.channels.isEmpty() && guide.programsByChannelId.isEmpty()

    companion object {
        val EMPTY = EpgSnapshot(XmlTvGuide.EMPTY, EpgGuideIndex.EMPTY, 0L, emptyList())
    }
}

data class EpgState(
    val snapshot: EpgSnapshot = EpgSnapshot.EMPTY,
    val syncing: Boolean = false,
    val lastAttemptEpochMs: Long = 0L,
    val sourcesTried: Int = 0,
    val sourcesFailed: Int = 0,
    /** Non-null when something went wrong, even if the swap still succeeded with fewer sources. */
    val lastFailure: EpgFailure? = null
)

sealed interface EpgSyncResult {
    data class Success(val snapshot: EpgSnapshot, val sourcesFailed: Int) : EpgSyncResult
    data class Failed(val failure: EpgFailure) : EpgSyncResult
}

/**
 * Loads and publishes the programme guide.
 *
 * Two guarantees define it, and both are direct answers to defects in the reference fork.
 *
 * **The swap happens only when something parsed.** The fork cleared every cache *before* downloading,
 * so if all sources failed it discarded a working guide, left the UI empty, and still stamped the
 * sync as successful. Here a failed run changes nothing that the user can see: [EpgState.snapshot]
 * keeps its previous value and only the failure fields move.
 *
 * **Concurrent calls share one run.** Several screens can ask for a sync while one is already
 * in flight; sharing the same [Deferred] means the sources are downloaded once, not once per caller.
 * The addon pays for each request upstream, so duplicated runs are not merely wasteful.
 */
class EpgRepository(
    private val fetcher: EpgDocumentFetcher,
    private val cache: EpgDiskCache,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxDecompressedBytes: Long = XmlTvParser.DEFAULT_MAX_DECOMPRESSED_BYTES
) {

    private val _state = MutableStateFlow(EpgState())
    val state: StateFlow<EpgState> = _state.asStateFlow()

    private val gate = Mutex()
    private var inFlight: Deferred<EpgSyncResult>? = null

    suspend fun sync(
        sources: List<EpgSource>,
        forceRefresh: Boolean = false
    ): EpgSyncResult {
        inFlight?.let { return it.await() }

        return gate.withLock {
            inFlight?.let { return@withLock it.await() }
            val run = scope.async(worker) { runSync(sources, forceRefresh) }
            inFlight = run
            try {
                run.await()
            } finally {
                inFlight = null
            }
        }
    }

    private suspend fun runSync(sources: List<EpgSource>, forceRefresh: Boolean): EpgSyncResult {
        val now = clock()
        _state.update {
            it.copy(syncing = true, lastAttemptEpochMs = now, lastFailure = null)
        }

        val guides = mutableListOf<XmlTvGuide>()
        val usedSourceIds = mutableListOf<String>()
        var failures = 0
        var lastFailure: EpgFailure? = null

        for (source in sources) {
            val bytes = loadBytes(source, now, forceRefresh)
            if (bytes == null) {
                failures++
                lastFailure = EpgFailure(source.name, EpgFailureReason.DOWNLOAD_FAILED)
                continue
            }
            val parsed = try {
                XmlTvParser.parse(
                    source = bytes.inputStream(),
                    referenceEpochMs = now,
                    maxDecompressedBytes = maxDecompressedBytes
                )
            } catch (limit: EpgLimitExceededException) {
                failures++
                lastFailure = EpgFailure(source.name, EpgFailureReason.TOO_LARGE)
                continue
            } catch (malformed: Exception) {
                failures++
                lastFailure = EpgFailure(source.name, EpgFailureReason.MALFORMED)
                continue
            }

            if (parsed.channels.isEmpty() && parsed.programsByChannelId.isEmpty()) continue
            guides.add(parsed)
            usedSourceIds.add(source.id)
        }

        return if (guides.isEmpty()) {
            // Nothing parsed: keep whatever the user was already looking at.
            val failure = lastFailure ?: EpgFailure("", EpgFailureReason.NOTHING_PARSED)
            _state.update {
                it.copy(
                    syncing = false,
                    sourcesTried = sources.size,
                    sourcesFailed = failures,
                    lastFailure = failure
                )
            }
            EpgSyncResult.Failed(failure)
        } else {
            val merged = EpgGuideMerge.merge(guides)
            val snapshot = EpgSnapshot(
                guide = merged,
                index = EpgGuideIndex.of(merged.channels),
                loadedAtEpochMs = now,
                sourceIds = usedSourceIds
            )
            _state.update {
                it.copy(
                    snapshot = snapshot,
                    syncing = false,
                    sourcesTried = sources.size,
                    sourcesFailed = failures,
                    lastFailure = lastFailure
                )
            }
            EpgSyncResult.Success(snapshot, failures)
        }
    }

    private suspend fun loadBytes(source: EpgSource, now: Long, forceRefresh: Boolean): ByteArray? {
        if (!forceRefresh) {
            cache.read(source.id, now)?.let { return it }
        }
        val downloaded = runCatching { fetcher.fetch(source.url) }.getOrNull() ?: return null
        if (downloaded.isEmpty()) return null
        cache.write(source.id, downloaded, now)
        return downloaded
    }
}
