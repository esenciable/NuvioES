@file:OptIn(UnstableApi::class)

package com.nuvio.tv.ext.livetv.ui

import android.content.Context
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking

/**
 * The one ExoPlayer the Live TV screen owns.
 *
 * ### One player, not one per channel
 *
 * Zapping changes channels many times a minute. Building a player per channel would throw away the
 * decoders, the renderers and the warmed network stack on every press; the reference fork went the
 * other way and kept a *pool*, which is the same problem with more bookkeeping. A single instance that
 * takes a new media source is both the cheapest and the simplest thing that holds up.
 *
 * The same instance backs the split-screen preview and the fullscreen surface. They are never composed
 * at the same time -- fullscreen replaces the screen -- so there is no contention, and leaving the
 * preview for fullscreen does not rebuild anything.
 *
 * ### Why the per-channel media source is built here
 *
 * Headers are part of the addon's stream, and a data source factory bakes them in. Rather than rebuild
 * the player when they change, each load builds a media source for that channel only and hands it to the
 * same player, which is what makes reuse across channels correct and not just cheap.
 */
@UnstableApi
internal class LiveTvPlayer(context: Context) {

    private val appContext = context.applicationContext
    private var loadedUrl: String? = null
    private var loadedHeaders: Map<String, String>? = null

    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(PlayerPlaybackNetworking.createDataSourceFactory(appContext))
        )
        // Nuvio's own memory tuning, so a low-end TV box gets the RAM-tiered buffers instead of the
        // stock defaults. Both are single calls into upstream, and neither modifies an upstream file.
        .setLoadControl(NuvioExoPlayerPerformanceHelper.buildLoadControl(appContext))
        .setBandwidthMeter(NuvioExoPlayerPerformanceHelper.buildBandwidthMeter(appContext))
        .build()
        .apply {
            // Browsing must be silent; fullscreen turns it back on. See the preview doc.
            volume = 0f
        }

    /**
     * Loads [url] on the shared player.
     *
     * [force] reloads even when the same stream is already playing -- that is what "Reintentar" after a
     * playback error does. Without it, walking from the preview into fullscreen on the same channel
     * would restart a stream that is already playing.
     */
    fun play(url: String, headers: Map<String, String>?, force: Boolean = false) {
        val resolvedHeaders = headers.orEmpty()
        if (!force && url == loadedUrl && resolvedHeaders == loadedHeaders) return

        loadedUrl = url
        loadedHeaders = resolvedHeaders
        val dataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(appContext, resolvedHeaders)
        val mediaSource = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(liveMediaItem(url))
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
    }

    fun release() {
        runCatching { player.release() }
    }
}

/**
 * Creates the one player and releases it with the composition.
 *
 * Owned by the screen, not by a surface: that is the whole reason the preview and fullscreen can share
 * an instance without either one releasing the other's player mid-transition.
 */
@Composable
internal fun rememberLiveTvPlayer(): LiveTvPlayer {
    val context = LocalContext.current
    val liveTvPlayer = remember { LiveTvPlayer(context) }
    DisposableEffect(liveTvPlayer) {
        onDispose { liveTvPlayer.release() }
    }
    return liveTvPlayer
}

/**
 * The split-screen preview: what the currently focused channel is broadcasting.
 *
 * ### Muted, on purpose
 *
 * The reference fork played its preview at full volume and started after a 450 ms pause on a focused
 * row, so moving around a list made the television shout at every stop. Nobody asked for audio while
 * browsing; the sound belongs to a channel the user deliberately opened. Anyone who wants sound presses
 * OK, which opens the fullscreen surface.
 *
 * ### Why the live target offset is set here
 *
 * A live stream is produced while it plays, so holding a deep buffer only adds distance from the live
 * edge. Upstream's player decides that for fullscreen playback and it is upstream's file; for the
 * preview, which is our own, the offset is set explicitly. This is the zero-conflict answer the plan
 * chose instead of editing upstream's buffer floor.
 */
@Composable
internal fun LiveTvPreviewSurface(
    liveTvPlayer: LiveTvPlayer,
    url: String,
    headers: Map<String, String>?,
    modifier: Modifier = Modifier
) {
    val focusHost = Modifier.focusProperties { canFocus = false }

    // The player is shared, so its volume is set on every preview mount: leaving fullscreen must not
    // leave a browsing list shouting.
    LaunchedEffect(liveTvPlayer, url, headers) {
        liveTvPlayer.player.volume = 0f
        liveTvPlayer.play(url, headers)
    }

    AndroidView(
        // Belt and braces with the View flags below: a preview must never take focus, because the remote
        // is driving the list and a panel that steals it strands the user.
        modifier = modifier.then(focusHost),
        factory = { viewContext ->
            // A TextureView, NOT PlayerView's SurfaceView, and that is the fix.
            //
            // A SurfaceView renders in its own layer outside Compose, so neither the rounded clip nor the
            // layout scaling reaches it: the frame came out anchored top-left and smaller than its
            // container, black to the right and below, with square corners against a rounded box. A
            // TextureView is drawn through the normal view hierarchy, so both are respected and the video
            // is letterboxed centred in its bounds instead of hanging off a corner.
            //
            // PlayerView is dropped rather than configured: its controller was already off, and its
            // SurfaceView was the only thing we used it for.
            TextureView(viewContext).also { view ->
                liveTvPlayer.player.setVideoTextureView(view)
            }
        },
        // NO onRelease here, and that asymmetry is the whole point.
        //
        // Clearing the player on release fixes the ghost for the surface that is being removed LAST --
        // but opening the fullscreen disposes THIS view AFTER the new one attached, so clearing here
        // wiped the surface the fullscreen had just set and the picture went black. Verified on device:
        // before this detach existed the fullscreen played fine, after it the capture came out 9.7 KB of
        // black. The fullscreen surface keeps its own onRelease, because the ghost appeared when THAT
        // one was torn down.
    )
}

private fun liveMediaItem(url: String): MediaItem = MediaItem.Builder()
    .setUri(url)
    .setLiveConfiguration(
        MediaItem.LiveConfiguration.Builder()
            .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
            .build()
    )
    .build()

/** Two or three segments behind the edge: enough to absorb one slow segment, close enough to be live. */
private const val LIVE_TARGET_OFFSET_MS = 12_000L
