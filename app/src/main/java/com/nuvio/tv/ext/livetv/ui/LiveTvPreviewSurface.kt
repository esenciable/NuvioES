@file:OptIn(UnstableApi::class)

package com.nuvio.tv.ext.livetv.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking

/**
 * The split-screen preview: what the currently focused channel is broadcasting.
 *
 * ### Muted, on purpose
 *
 * The reference fork played its preview at full volume and started after a 450 ms pause on a focused
 * row, so moving around a list made the television shout at every stop. Nobody asked for audio while
 * browsing; the sound belongs to a channel the user deliberately opened. Anyone who wants sound presses
 * OK.
 *
 * ### Released with the screen, not with the process
 *
 * The reference fork kept a `@Singleton` player whose `yield()` and `release()` were never called
 * anywhere -- dead code guarding a real leak -- and left it running when the user moved on. Here the
 * player is created by the composition and released by `onDispose`, so leaving the screen frees it, and
 * a screen with no preview never builds one at all.
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
    url: String,
    headers: Map<String, String>?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Rebuilt when the headers change, because they are baked into the data source factory.
    val player = remember(headers) { createPreviewPlayer(context, headers.orEmpty()) }

    // Keyed on the player itself, so a rebuilt one releases its predecessor instead of leaking it.
    DisposableEffect(player) {
        onDispose { runCatching { player.release() } }
    }

    LaunchedEffect(player, url) {
        player.setMediaItem(liveMediaItem(url))
        player.prepare()
        player.playWhenReady = true
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                useController = false
                this.player = player
            }
        }
    )
}

private fun createPreviewPlayer(context: Context, headers: Map<String, String>): ExoPlayer {
    val dataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(context, headers)
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .build()
        .apply {
            // Browsing must be silent. See the class doc.
            volume = 0f
        }
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
