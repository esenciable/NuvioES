package com.nuvio.tv.ext.livetv.core

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo

/**
 * The load-error policy for live channels: HTTP failures get a few retries with backoff.
 *
 * ### The failure this absorbs
 *
 * The device log showed it exactly: a live segment answered `404` and ExoPlayer treated it as a fatal
 * source error, killing playback. On a live stream a 4xx is usually a playlist or segment that rotted
 * for a second -- the playlist rotates under the player, or the resolved session aged out -- so a
 * couple of retries with backoff let the player re-fetch the refreshed playlist and carry on. Media3's
 * own default never retries a 4xx by design, which for video on demand is right and for live is not.
 *
 * ### What stays fatal, on purpose
 *
 * `401` and `403` do not retry: they mean the credentials in hand are wrong, and retrying a few hundred
 * milliseconds later reproduces the same rejection -- that fix belongs to the resolver, not to the
 * backoff loop. Anything that is not an HTTP status code is delegated to media3's default policy, so
 * its behaviour for parsing and network errors is unchanged.
 *
 * The decision table lives in [retryDelayMsFor] as a pure function: the policy wrapper only unwraps
 * the exception, and the table is unit-testable without an Android device. Extending
 * [DefaultLoadErrorHandlingPolicy] rather than implementing the interface keeps media3's fallback
 * selection and minimum-retry bookkeeping untouched.
 */
@UnstableApi
internal class LiveTvLoadErrorHandlingPolicy(
    private val maxRetries: Int = DEFAULT_MAX_RETRIES
) : DefaultLoadErrorHandlingPolicy(/* minLoadableRetryCount = */ maxRetries + 1) {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
        val responseCode =
            (loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        return retryDelayMsFor(responseCode, loadErrorInfo.errorCount, maxRetries)
            ?: super.getRetryDelayMsFor(loadErrorInfo)
    }

    internal companion object {

        /** Small by design: a live edge that is still broken after this many tries is truly broken. */
        const val DEFAULT_MAX_RETRIES = 3

        /** Backoff base, so each retry waits longer than the one before. */
        const val BACKOFF_BASE_MS = 500L

        /**
         * The decision table. Returns the retry delay, [C.TIME_UNSET] for a deliberate fatal, or `null`
         * for "not ours to decide" -- the caller then delegates to media3's default policy.
         */
        internal fun retryDelayMsFor(responseCode: Int?, errorCount: Int, maxRetries: Int): Long? = when {
            responseCode == null -> null

            // Wrong credentials: retrying cannot fix them, and hammering the server with the same
            // rejection buys nothing. Re-resolution is the fix, and it lives elsewhere.
            responseCode == 401 || responseCode == 403 -> C.TIME_UNSET

            responseCode >= 400 && errorCount <= maxRetries -> BACKOFF_BASE_MS * errorCount

            // Budget spent: stop here rather than delegating, so media3's defaults cannot quietly
            // extend the retry loop past what this policy grants.
            responseCode >= 400 -> C.TIME_UNSET

            else -> null
        }
    }
}
