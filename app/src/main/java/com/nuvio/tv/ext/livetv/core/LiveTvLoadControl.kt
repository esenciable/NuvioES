package com.nuvio.tv.ext.livetv.core

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultLoadControl.Builder
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper

/**
 * The `LoadControl` for live channels: a deliberately DEEP buffer.
 *
 * ### The measurement this comes from
 *
 * Same machine, same network, same sources, compared across two trees:
 *
 * | tree | MIN | MAX | result |
 * | --- | --- | --- | --- |
 * | our pin (= upstream-dev) | 15 s | 45 s | **stutters** |
 * | the reference fork | 40 s | 120 s | **plays fine** |
 *
 * The fork RAISED the buffer on top of the same upstream. That is the opposite of the usual advice, and
 * the reason is what `minBufferMs` means: it is the amount the player tries to hold **at all times**. At
 * 15 s a single slow segment drains it and playback rebuffers; at 40 s there is slack.
 *
 * This corrects the plan. The PRD asked for a live target offset to pull the session closer to the live
 * edge, reasoning that live wants a SHORT buffer. The measured complaint is not latency, it is stability,
 * so the live floor goes deep on purpose. The cost is real and accepted: the session sits further from
 * the live edge than it could.
 *
 * ### Why this lives here and not in upstream's file
 *
 * `buildLoadControl` reads module-level mutable fields (`minBufferMs`, `maxBufferMs`), so it cannot be
 * parameterised without editing that file, and editing it is a sixth hook. Everything needed is public
 * -- the allocator, its segment size, the target buffer and the playback gates -- so the live control is
 * built here instead, from our side of the boundary.
 */
@UnstableApi
object LiveTvLoadControl {

    /** Deep enough to absorb a slow segment. See the table above. */
    const val LIVE_MIN_BUFFER_MS = 40_000

    /** A ceiling, so live cannot hold two minutes of a stream nobody will seek into. */
    const val LIVE_MAX_BUFFER_MS = 120_000

    fun build(): DefaultLoadControl {
        val performance = NuvioExoPlayerPerformanceHelper
        val builder = Builder()
            .setBufferDurationsMs(
                LIVE_MIN_BUFFER_MS,
                LIVE_MAX_BUFFER_MS,
                performance.bufferForPlaybackMs,
                performance.bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(performance.backBufferMs, true)

        if (performance.enabled) {
            // Upstream's native arena pooling, kept because a low-end TV box is exactly where a live
            // stream is most expensive and where this tuning exists for a reason.
            builder.setAllocator(
                DefaultAllocator(
                    /* trimOnReset = */ true,
                    NuvioExoPlayerPerformanceHelper.DEFAULT_NUVIO_ALLOCATOR_SEGMENT_SIZE,
                    /* individualAllocationSize = */ 64,
                    /* dedicatedThread = */ true
                )
            )
            val targetBytes = performance.targetBufferSizeMb.coerceAtLeast(1) * 1024 * 1024
            builder.setTargetBufferBytes(targetBytes)
        }
        return builder.build()
    }
}
