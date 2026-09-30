@file:OptIn(UnstableApi::class)

package com.nuvio.tv.ext.livetv.ui

import android.view.TextureView
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
import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.PlaybackException
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nuvio.tv.ext.livetv.core.LiveTvLoadControl
import com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The ONE video view for the whole feature, used by the preview panel and by fullscreen.
 *
 * ### Why one composable, and why it had to happen
 *
 * The preview used a `TextureView` and fullscreen used a `PlayerView`/`SurfaceView`. Different view types
 * mean different physics: `TextureView` re-applies geometry every draw and gets clipped by its Compose
 * parent, while a `SurfaceView` keeps its own buffer **and** its own geometry. Every defect this feature
 * had -- the black screen, the ghost frame under the list, the video not filling its container -- was a
 * consequence of that split, and each fix in one surface left the other broken. One composable ends it.
 *
 * ### `TextureView`, not `PlayerView`
 *
 * A `PlayerView`'s `SurfaceView` renders in its own layer outside Compose: the rounded clip of the preview
 * panel never reached it (the video came out square-cornered against a rounded box), and its stale buffer
 * and geometry survived channel changes (the "previous channel around the new one" defect). A `TextureView`
 * is drawn through the normal view hierarchy, so clipping works, and its stale content is fully replaced by
 * the next frame. The cost is GPU composition and no secure/tunnelled video; this feature plays plain
 * addon-resolved HLS over HTTP with no DRM, so the cost is acceptable.
 *
 * ### The cover, which is the fork's mechanism applied consistently
 *
 * A `TextureView` keeps its last drawn content and ExoPlayer letterboxes a video that does not fill the
 * view, so after a channel change the new frame covers only part of the panel and the previous channel
 * stays visible in the rest: two live pictures in one box, reported from the device.
 *
 * The fix is a **cover** drawn as a sibling **after** the view, removed once the stream is on screen. It is
 * drawn *after* because an opaque Compose sibling does cover a `PlayerView`'s `SurfaceView` -- proven by the
 * house player's own opaque `LoadingOverlay` -- while `alpha` on the view **does not work and deadlocks**:
 * a view Compose does not draw never renders a frame, so a first-frame signal on it never arrives. Verified
 * on device; that attempt blacked the preview out.
 *
 * The cover also hides the buffering gap: while the next channel prepares, the panel shows its own
 * background instead of a frozen frame pretending to be live. And when a stream turns out to have no video
 * track, the cover lifts on `STATE_READY` instead of staying up forever -- a radio-style IPTV channel never
 * fires the first-frame event and it plays perfectly fine.
 *
 * Callers must clip their own parent for rounded corners (`Modifier.clip`) -- with a `TextureView` that
 * works, which is the other half of why it replaced `PlayerView`.
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

    // The view exists once per URL: a fresh TextureView starts empty, which is what stops the previous
    // channel's frame from lingering in a panel that was just re-attached. Keyed on the headers too,
    // because they are baked into the data source factory and therefore into the playback path.
    DisposableEffect(url, headers) {
        onDispose {
            // An ExoPlayer renders into ONE surface. Detaching on teardown keeps a disposed view from
            // keeping its last frame, which is how the ghost under the list appeared. Asymmetric on
            // purpose: this is safe here because the two surfaces are mutually exclusive branches and
            // never share a player any more.
            liveTvPlayer.player.setVideoTextureView(null)
        }
    }

    // Volume on every mount: leaving fullscreen must not leave a browsing list shouting.
    LaunchedEffect(liveTvPlayer, url, headers, muted) {
        liveTvPlayer.player.volume = if (muted) 0f else 1f
        liveTvPlayer.play(url, headers)
    }

    Box(modifier = modifier) {
        AndroidView(
            // A preview must never take focus, because the remote is driving the list and a panel that
            // steals it strands the user. A TextureView is not focusable to begin with.
            modifier = Modifier.fillMaxSize().focusProperties { canFocus = false },
            factory = { viewContext ->
                TextureView(viewContext).also { view ->
                    liveTvPlayer.player.setVideoTextureView(view)
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
        // The LIVE control, not upstream's. Upstream's floor is 15s and a live stream stutters on it; see
        // LiveTvLoadControl for the measurement. This is where the plan's biggest correction lives, and it
        // costs zero upstream files.
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                PlayerPlaybackNetworking.createDataSourceFactory(appContext, loadedHeaders.orEmpty())
            )
        )
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
