package com.nuvio.tv.ext.livetv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.nuvio.tv.R
import com.nuvio.tv.ext.livetv.core.LiveTvLoadControl
import com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The ONE video view for the whole feature, used by the preview panel and by fullscreen.
 *
 * ### A real `PlayerView`, and `ZOOM` instead of `FIT`
 *
 * The version before this one used a raw `TextureView` with hand-written fill math. `PlayerView` owns
 * the surface lifecycle and the `AspectRatioFrameLayout`, so `resizeMode = RESIZE_MODE_ZOOM` gives the
 * "no leftover unpainted region" guarantee without any transform written by hand. `FIT` -- which is what
 * the fork uses -- is the mode that letterboxes, and the letterbox region is exactly where the previous
 * channel's frame survived a channel change: nothing ever overwrites it.
 *
 * `ZOOM` fills the frame and crops. That crop is the documented trade, and it is accepted deliberately:
 * a channel viewer sees a live picture, not a geometry exercise.
 *
 * The **TextureView** surface type is kept, and it is not a line of Kotlin: `surface_type` is read from
 * XML attributes during construction and has no setter, so the view is inflated from
 * `R.layout.livetv_video_surface` (`app:surface_type="texture_view"`). A `SurfaceView` cannot be clipped
 * by its Compose parent, and the preview panel's rounded corners are a real device-reported requirement
 * this feature already paid once to fix.
 *
 * ### The cover, which is the fork's mechanism applied consistently
 *
 * A player keeps its last frame and ExoPlayer letterboxes a video that does not fill the view, so after a
 * channel change the new frame covers only part of the panel and the previous channel stays visible in the
 * rest: two live pictures in one box, reported from the device.
 *
 * The fix is a **cover** drawn as a sibling **after** the view, removed once the stream is on screen. It is
 * drawn *after* because an opaque Compose sibling does cover a `PlayerView`'s `SurfaceView` -- proven by the
 * house player's own opaque `LoadingOverlay` -- while `alpha` on the view **does not work and deadlocks**: a
 * view Compose does not draw never renders a frame, so a first-frame signal on it never arrives. Verified
 * on device; that attempt blacked the preview out.
 *
 * The cover also hides the buffering gap: while the next channel prepares, the panel shows its own
 * background instead of a frozen frame pretending to be live. And when a stream turns out to have no video
 * track, the cover lifts on `STATE_READY` instead of staying up forever -- a radio-style IPTV channel never
 * fires the first-frame event and it plays perfectly fine.
 */
@Composable
internal fun LiveTvVideoSurface(
    liveTvPlayer: LiveTvPlayer,
    url: String,
    headers: Map<String, String>?,
    muted: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LaunchedEffect(liveTvPlayer, url, headers, muted) {
        liveTvPlayer.player.volume = if (muted) 0f else 1f
        liveTvPlayer.play(url, headers)
    }

    Box(modifier = modifier) {
        AndroidView(
            // A video view must never take focus, because the remote is driving the list and a panel that
            // steals it strands the user.
            modifier = Modifier.fillMaxSize().focusProperties { canFocus = false },
            factory = { viewContext ->
                // The layout resource is what selects the TextureView surface type; see the class docs.
                (LayoutInflater.from(viewContext)
                    .inflate(R.layout.livetv_video_surface, null, false) as PlayerView)
                    .apply {
                        // No controller: our surfaces draw their own interface, and the controller would consume
                        // the very directions the zapping needs. (`useController = false` also comes from the XML.)
                        isFocusable = false
                        isFocusableInTouchMode = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        // The shutter is opaque black and closed over a dead player: with it, a stale frame
                        // has nothing to show through. Default keepContentOnPlayerReset (false) is left alone.
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        this.player = liveTvPlayer.player
                    }
            }
        )

        if (liveTvPlayer.renderedGeneration != liveTvPlayer.pendingGeneration) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(NuvioTheme.colors.BackgroundElevated)
            )
        }
    }
}

/**
 * A live player wrapper: one `ExoPlayer` plus the knowledge of whether the stream currently on screen has
 * drawn.
 *
 * The pending/on-screen pair lives on the player rather than in each composable, because both surfaces must
 * agree on it. It is also what fixes a live bug in the earlier cover: a stream with no video track never
 * fires `onRenderedFirstFrame`, so a cover gated only on that event stayed up forever on a channel that was
 * playing perfectly. `STATE_READY` lifts it too, and an error hands the screen to the failure state.
 */
@UnstableApi
internal class LiveTvPlayer(context: Context) {

    private val appContext = context.applicationContext
    private var loadedUrl: String? = null
    private var loadedHeaders: Map<String, String>? = null

    /** Bumped every time a new stream is asked for. */
    var pendingGeneration: Int by mutableStateOf(0)
        private set

    /** Bumped when the stream on screen has actually shown something. */
    var renderedGeneration: Int by mutableStateOf(0)
        private set

    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                PlayerPlaybackNetworking.createDataSourceFactory(appContext)
            )
        )
        // The LIVE control, not upstream's. Upstream's floor is 15s and a live stream stutters on it; see
        // LiveTvLoadControl for the measurement. This is where the plan's biggest correction lives, and it
        // costs zero upstream files.
        .setLoadControl(LiveTvLoadControl.build())
        .setBandwidthMeter(NuvioExoPlayerPerformanceHelper.buildBandwidthMeter(appContext))
        .build()
        .apply {
            // Fullscreen turns volume back on. See the surface's docs.
            volume = 0f
        }

    init {
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                renderedGeneration = pendingGeneration
            }

            // STATE_READY as well, because a stream with no video track never fires the event above and the
            // cover would stay up forever on a channel that is playing perfectly. Verified against the fork:
            // it flips the same flag on both events.
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) renderedGeneration = pendingGeneration
            }

            // The failure state owns the screen from here; a cover under it would only fight it.
            override fun onPlayerError(error: PlaybackException) {
                renderedGeneration = pendingGeneration
            }
        })
    }

    /**
     * Loads [url] on this player.
     *
     * [force] reloads even when the same stream is already playing -- that is what "Reintentar" after a
     * playback error does. Without it, walking from the preview into fullscreen on the same channel would
     * restart a stream that is already playing.
     */
    fun play(url: String, headers: Map<String, String>?, force: Boolean = false) {
        val resolvedHeaders = headers.orEmpty()
        if (!force && loadedUrl == url && loadedHeaders == resolvedHeaders) return

        pendingGeneration++
        val mediaSource = DefaultMediaSourceFactory(
            PlayerPlaybackNetworking.createDataSourceFactory(appContext, resolvedHeaders)
        ).createMediaSource(liveMediaItem(url))
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
        loadedUrl = url
        loadedHeaders = resolvedHeaders
    }

    private fun liveMediaItem(url: String): MediaItem = MediaItem.Builder()
        .setUri(url)
        .setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
                .build()
        )
        .build()

    fun release() {
        runCatching {
            player.stop()
            player.clearMediaItems()
            player.release()
        }
    }

    /** Two or three segments behind the edge: enough to absorb a slow segment, close enough to be live. */
    private companion object {
        const val LIVE_TARGET_OFFSET_MS = 12_000L
    }
}

/** Created for the screen's lifetime and released when the screen leaves. */
@Composable
internal fun rememberLiveTvPlayer(): LiveTvPlayer {
    val context = LocalContext.current
    val player = remember { LiveTvPlayer(context.applicationContext) }
    DisposableEffect(Unit) { onDispose { player.release() } }
    return player
}
